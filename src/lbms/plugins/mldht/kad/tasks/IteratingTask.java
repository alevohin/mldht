package lbms.plugins.mldht.kad.tasks;

import lbms.plugins.mldht.kad.DHT;
import lbms.plugins.mldht.kad.DHTConstants;
import lbms.plugins.mldht.kad.Key;
import lbms.plugins.mldht.kad.Node;
import lbms.plugins.mldht.kad.RPCServer;
import lbms.plugins.mldht.kad.RPCState;
import lbms.plugins.mldht.kad.tasks.IterativeLookupCandidates.LookupGraphNode;
import lbms.plugins.mldht.kad.utils.AddressUtils;
import lbms.plugins.mldht.kad.utils.PopulationEstimator;
import lbms.plugins.mldht.kad.DHT.LogLevel;

import java.util.stream.Collectors;

public abstract class IteratingTask extends TargetedTask {
	
	ClosestSet closest;
	IterativeLookupCandidates todo;
	
	public IteratingTask(Key target, RPCServer srv, Node node) {
		super(target, srv, node);
		todo = new IterativeLookupCandidates(target, node.getDHT().getMismatchDetector());
		closest = new ClosestSet(target, DHTConstants.MAX_ENTRIES_PER_BUCKET);
	}
	
	@Override
	public int getTodoCount() {
		return (int) todo.cand().count();
	}
	
	public String closestDebug() {
		return this.closest.ids().<String>map(k -> {
			return k + "  " + targetKey.distance(k) + " " + PopulationEstimator.distanceToDouble(k, targetKey) + " src:" + todo.allCand().unordered().filter(e -> e.getKey().getID().equals(k)).findAny().get().getValue().sources.size();
		}).collect(Collectors.joining("\n"));
	}
	
	protected void logClosest() {
		Key farthest = closest.tail();
		
		DHT.log(this.toString() + "\n" +

				"Task "+ getTaskID() +"  done " + counts + " " + closest + "\n" + targetKey + "\n" + closestDebug()  + "\n" +
				

				todo.allCand().sorted(todo.comp()).filter(me -> {
					return targetKey.threeWayDistance(me.getKey().getID(), farthest) <= 0;
				}).<String>map(e -> {
					LookupGraphNode node = e.getValue();
					
					return String.format("%s %s %s %s src:%d-%d call:%d rsp:%d acc:%d %s",
							e.getKey().getID(),
							targetKey.distance(e.getKey().getID()),
							AddressUtils.toString(e.getKey().getAddress()),
							node.tainted ? "!" : " ",
							node.sources.size(),
							node.previouslyFailedCount,
							node.calls.size(),
							node.calls.stream().filter(c -> c.state() == RPCState.RESPONDED).count(),
							node.acceptedResponse ? 1 : 0,
							e.getValue().sources.stream().map(LookupGraphNode::toKbe).collect(Collectors.toList())
						);

				}).collect(Collectors.joining("\n"))

				, LogLevel.Verbose);

	}

}
