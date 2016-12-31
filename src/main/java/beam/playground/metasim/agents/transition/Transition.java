package beam.playground.metasim.agents.transition;

import java.util.HashSet;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.anarres.graphviz.builder.GraphVizScope;
import org.matsim.core.controler.MatsimServices;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.states.State;
import beam.playground.metasim.services.BeamServices;

public interface Transition {
	public State getFromState();
	public State getToState();
	public Boolean isContingent();
	public Boolean isAvailableTo(BeamAgent agent);
	public void performTransition(BeamAgent agent);
	public void initialize(State fromState, State toState, Boolean isContingent, BeamServices beamServices, MatsimServices matsimServices);
	
	public abstract class Default implements Transition, GraphVizScope {
		protected BeamServices beamServices;
		protected MatsimServices matsimServices;
		protected State fromState, toState;
		protected Boolean isContingent;

		@Override
		public void initialize(State fromState, State toState, Boolean isContingent, BeamServices beamServices, MatsimServices matsimServices){
			this.fromState = fromState;
			this.toState = toState;
			this.isContingent = isContingent;
			this.beamServices = beamServices;
			this.matsimServices = matsimServices;
		}

		@Override
		public State getFromState() {
			return fromState;
		}

		@Override
		public State getToState() {
			return toState;
		}

		@Override
		public Boolean isContingent() {
			return isContingent;
		}

		@Override
		public Boolean isAvailableTo(BeamAgent agent) {
			return true;
		}

		public String toString(){
			return "Transition:"+fromState.getName()+"_To_"+toState.getName();
		}
	}
}
