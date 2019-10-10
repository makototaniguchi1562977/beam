package beam.utils.beamToVia.apps

import beam.utils.beamToVia.beamEvent.{BeamEvent, BeamPathTraversal}
import beam.utils.beamToVia.{BeamEventsReader, LinkCoordinate, Point, Writer}

import scala.collection.mutable

object FindIdsInCircles extends App {
  val sourceFileName = "40.events.csv"

  val dirPath = "D:/Work/beam/September2019/Runs/AnoterRun-40iter/"
  val sourcePath = dirPath + sourceFileName
  val baseOutputPath = dirPath + sourceFileName + ".in_SF"

  val networkPath = dirPath + "output_network.xml"
  //val networkPath = dirPath + "physSimNetwork.xml"
  //val networkPath = dirPath + "physSimNetwork.HQ.xml"

  val networkXml = xml.XML.loadFile(networkPath)
  val nodes = LinkCoordinate.parseNodes(networkXml)

  case class Circle(x: Double, y: Double, r: Double) {
    val rSquare: Double = r * r
  }

  val sfCircle = Circle(548966, 4179000, 5000)
  def pointIsInteresting(point: Point): Boolean = true

  val interestingNodes = nodes
    .foldLeft(mutable.Map.empty[Int, Point]) {
      case (selectedNodes, (nodeId, point)) if pointIsInteresting(point) => selectedNodes += nodeId -> point
      case (selectedNodes, _)                                            => selectedNodes
    }
    .toMap

  val interestingLinks = LinkCoordinate
    .parseNetwork(networkXml, interestingNodes)
    .foldLeft(mutable.HashSet.empty[Int]) {
      case (links, (linkId, _)) => links += linkId
    }

  class CircleAccumulator() {
    var interestingVehicles = mutable.HashSet.empty[String]

    def process(event: BeamEvent): Unit = event match {
      case pte: BeamPathTraversal if pte.linkIds.exists(interestingLinks.contains) =>
        interestingVehicles += pte.vehicleId

      case _ =>
    }
  }

  val vehiclesInCircle = BeamEventsReader
    .fromFileFoldLeft[CircleAccumulator](sourcePath, new CircleAccumulator(), (acc, event) => {
      acc.process(event)
      acc
    })
    .getOrElse(new CircleAccumulator())
    .interestingVehicles

  val vehiclesPath = baseOutputPath + ".vehicles.txt"
  Writer.writeSeqOfString(vehiclesInCircle, vehiclesPath)
  Console.println("vehicles written into " + vehiclesPath)
}
