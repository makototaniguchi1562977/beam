package beam.integration

import java.io.File

import akka.actor._
import akka.testkit.{ImplicitSender, TestKit}
import beam.agentsim.agents.PersonTestUtil
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.ridehail.{RideHailIterationHistory, RideHailSurgePricingManager}
import beam.agentsim.events.PathTraversalEvent
import beam.router.BeamRouter
import beam.router.Modes.BeamMode
import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import beam.router.r5.DefaultNetworkCoordinator
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamMobsim, BeamServices, BeamServicesImpl}
import beam.utils.BeamVehicleUtils.{readBeamVehicleTypeFile, readFuelTypeFile}
import beam.utils.TestConfigUtils.{testConfig, testOutputDir}
import beam.utils.{NetworkHelper, NetworkHelperImpl}
import com.google.inject.util.Providers
import com.google.inject.{AbstractModule, Guice}
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.events.{ActivityEndEvent, Event, PersonDepartureEvent, PersonEntersVehicleEvent}
import org.matsim.api.core.v01.population.{Activity, Leg}
import org.matsim.core.controler.{ControlerI, MatsimServices, OutputDirectoryHierarchy}
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.events.{EventsManagerImpl, EventsUtils}
import org.matsim.core.scenario.ScenarioUtils
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.mockito.MockitoSugar

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.language.postfixOps

class SingleModeSpec
    extends TestKit(
      ActorSystem(
        "single-mode-test",
        ConfigFactory
          .parseString("""
              akka.test.timefactor = 10
            """)
          .withFallback(testConfig("test/input/sf-light/sf-light.conf").resolve())
      )
    )
    with WordSpecLike
    with Matchers
    with ImplicitSender
    with MockitoSugar
    with BeforeAndAfterAll
    with Inside {

  private val BASE_PATH = new File("").getAbsolutePath
  private val OUTPUT_DIR_PATH = BASE_PATH + "/" + testOutputDir + "single-mode-test"

  var router: ActorRef = _
  var geoUtil: GeoUtils = _
  var scenario: Scenario = _
  var services: BeamServices = _
  var networkCoordinator: DefaultNetworkCoordinator = _
  var beamCfg: BeamConfig = _
  var tollCalculator: TollCalculator = _

  override def beforeAll: Unit = {
    beamCfg = BeamConfig(system.settings.config)

    val vehTypes = {
      val fuelTypes = readFuelTypeFile(beamCfg.beam.agentsim.agents.vehicles.beamFuelTypesFile)
      TrieMap(
        readBeamVehicleTypeFile(beamCfg.beam.agentsim.agents.vehicles.beamVehicleTypesFile, fuelTypes).toSeq: _*
      )
    }

    val overwriteExistingFiles =
      OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles
    val outputDirectoryHierarchy =
      new OutputDirectoryHierarchy(OUTPUT_DIR_PATH, overwriteExistingFiles)
    outputDirectoryHierarchy.createIterationDirectory(0)

    geoUtil = new GeoUtilsImpl(beamCfg)

    networkCoordinator = DefaultNetworkCoordinator(beamCfg)
    networkCoordinator.loadNetwork()
    networkCoordinator.convertFrequenciesToTrips()

    val networkHlper = new NetworkHelperImpl(networkCoordinator.network)

    val fareCalculator = new FareCalculator(beamCfg.beam.routing.r5.directory)
    tollCalculator = new TollCalculator(beamCfg)
    val matsimConfig = new MatSimBeamConfigBuilder(system.settings.config).buildMatSamConf()
    scenario = ScenarioUtils.loadScenario(matsimConfig)

    val matsimSvc = mock[MatsimServices]
    when(matsimSvc.getControlerIO).thenReturn(outputDirectoryHierarchy)
    when(matsimSvc.getScenario).thenReturn(scenario)

    val injector = Guice.createInjector(new AbstractModule() {
      protected def configure(): Unit = {
        bind(classOf[BeamConfig]).toInstance(beamCfg)
        bind(classOf[GeoUtils]).toInstance(geoUtil)
        bind(classOf[NetworkHelper]).toInstance(networkHlper)
        bind(classOf[ControlerI]).toProvider(Providers.of(null))
      }
    })

    services = new BeamServicesImpl(injector)
    services.matsimServices = matsimSvc
    services.modeChoiceCalculatorFactory = ModeChoiceCalculator(
      services.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass,
      services
    )

    scenario.getPopulation.getPersons.values.asScala
      .foreach(p => PersonTestUtil.putDefaultBeamAttributes(p, BeamMode.allTripModes))

    router = system.actorOf(
      BeamRouter.props(
        services,
        networkCoordinator.transportNetwork,
        networkCoordinator.network,
        scenario,
        new EventsManagerImpl(),
        scenario.getTransitVehicles,
        fareCalculator,
        tollCalculator
      ),
      "router"
    )
    services.beamRouter = router
  }

  override def afterAll: Unit = {
    shutdown()
    router = null
    geoUtil = null
    scenario = null
    services = null
    networkCoordinator = null
    beamCfg = null
  }

  "The agentsim" must {
    "let everybody walk when their plan says so" in {
      scenario.getPopulation.getPersons
        .values()
        .forEach { person =>
          {
            person.getSelectedPlan.getPlanElements.asScala.collect {
              case leg: Leg =>
                leg.setMode("walk")
            }
          }
        }
      val events = mutable.ListBuffer[Event]()
      val eventsManager = EventsUtils.createEventsManager()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case event: PersonDepartureEvent =>
                events += event
              case _ =>
            }
          }
        }
      )
      val mobsim = new BeamMobsim(
        services,
        networkCoordinator.transportNetwork,
        tollCalculator,
        scenario,
        eventsManager,
        system,
        new RideHailSurgePricingManager(services),
        new RideHailIterationHistory()
      )
      mobsim.run()
      events.foreach {
        case event: PersonDepartureEvent =>
          assert(event.getLegMode == "walk" || event.getLegMode == "be_a_tnc_driver")
      }
    }

    "let everybody take transit when their plan says so" in {
      scenario.getPopulation.getPersons
        .values()
        .forEach { person =>
          person.getSelectedPlan.getPlanElements.asScala.collect {
            case leg: Leg =>
              leg.setMode("walk_transit")
          }
        }
      val events = mutable.ListBuffer[Event]()
      val eventsManager = EventsUtils.createEventsManager()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case event: PersonDepartureEvent =>
                events += event
              case _ =>
            }
          }
        }
      )
      val mobsim = new BeamMobsim(
        services,
        networkCoordinator.transportNetwork,
        tollCalculator,
        scenario,
        eventsManager,
        system,
        new RideHailSurgePricingManager(services),
        new RideHailIterationHistory()
      )
      mobsim.run()
      events.foreach {
        case event: PersonDepartureEvent =>
          assert(
            event.getLegMode == "walk" || event.getLegMode == "walk_transit" || event.getLegMode == "be_a_tnc_driver"
          )
      }
    }

    "let everybody take drive_transit when their plan says so" in {
      // Here, we only set the mode for the first leg of each tour -- prescribing a mode for the tour,
      // but not for individual legs except the first one.
      // We want to make sure that our car is returned home.
      scenario.getPopulation.getPersons
        .values()
        .forEach { person =>
          {
            val newPlanElements = person.getSelectedPlan.getPlanElements.asScala.collect {
              case activity: Activity if activity.getType == "Home" =>
                Seq(activity, scenario.getPopulation.getFactory.createLeg("drive_transit"))
              case activity: Activity =>
                Seq(activity)
              case leg: Leg =>
                Nil
            }.flatten
            if (newPlanElements.last.isInstanceOf[Leg]) {
              newPlanElements.remove(newPlanElements.size - 1)
            }
            person.getSelectedPlan.getPlanElements.clear()
            newPlanElements.foreach {
              case activity: Activity =>
                person.getSelectedPlan.addActivity(activity)
              case leg: Leg =>
                person.getSelectedPlan.addLeg(leg)
            }
          }
        }
      val events = mutable.ListBuffer[Event]()
      val eventsManager = EventsUtils.createEventsManager()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case event @ (_: PersonDepartureEvent | _: ActivityEndEvent) =>
                events += event
                if (events.size % 10000 == 0)
                  println(s"Event size: ${events.size}")
              case _ =>
            }
          }
        }
      )
      val mobsim = new BeamMobsim(
        services,
        networkCoordinator.transportNetwork,
        tollCalculator,
        scenario,
        eventsManager,
        system,
        new RideHailSurgePricingManager(services),
        new RideHailIterationHistory()
      )
      mobsim.run()
      events.collect {
        case event: PersonDepartureEvent =>
          // drive_transit can fail -- maybe I don't have a car
          assert(
            event.getLegMode == "walk" || event.getLegMode == "walk_transit" || event.getLegMode == "drive_transit" || event.getLegMode == "be_a_tnc_driver"
          )
      }
      val eventsByPerson = events.groupBy(_.getAttributes.get("person"))
      val filteredEventsByPerson = eventsByPerson.filter {
        _._2
          .filter(_.isInstanceOf[ActivityEndEvent])
          .sliding(2)
          .exists(
            pair => pair.forall(activity => activity.asInstanceOf[ActivityEndEvent].getActType != "Home")
          )
      }
      eventsByPerson.map {
        _._2.span {
          case event: ActivityEndEvent if event.getActType == "Home" =>
            true
          case _ =>
            false
        }
      }
      // TODO: Test that what can be printed with the line below makes sense (chains of modes)
      //      filteredEventsByPerson.map(_._2.mkString("--\n","\n","--\n")).foreach(print(_))
    }

    "let everybody drive when their plan says so" in {
      scenario.getPopulation.getPersons
        .values()
        .forEach { person =>
          {
            person.getSelectedPlan.getPlanElements.asScala.collect {
              case leg: Leg =>
                leg.setMode("car")
            }
          }
        }
      val eventsManager = EventsUtils.createEventsManager()
      val events = mutable.ListBuffer[Event]()
      eventsManager.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case event @ (_: PersonDepartureEvent | _: ActivityEndEvent | _: PathTraversalEvent |
                  _: PersonEntersVehicleEvent) =>
                events += event
              case _ =>
            }
          }
        }
      )

      val mobsim = new BeamMobsim(
        services,
        networkCoordinator.transportNetwork,
        tollCalculator,
        scenario,
        eventsManager,
        system,
        new RideHailSurgePricingManager(services),
        new RideHailIterationHistory()
      )
      mobsim.run()
      events.collect {
        case event: PersonDepartureEvent =>
          // Wr still get some failing car routes.
          // TODO: Find root cause, fix, and remove "walk" here.
          // See SfLightRouterSpec.
          assert(event.getLegMode == "walk" || event.getLegMode == "car" || event.getLegMode == "be_a_tnc_driver")
      }
    }
  }

}
