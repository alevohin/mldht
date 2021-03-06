/*
 *    This file is part of mlDHT.
 * 
 *    mlDHT is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 2 of the License, or
 *    (at your option) any later version.
 * 
 *    mlDHT is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 * 
 *    You should have received a copy of the GNU General Public License
 *    along with mlDHT.  If not, see <http://www.gnu.org/licenses/>.
 */
package lbms.plugins.mldht.kad;

import static the8472.bencode.Utils.prettyPrint;

import lbms.plugins.mldht.kad.messages.MessageBase;
import lbms.plugins.mldht.kad.messages.MessageBase.Method;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * @author Damokles
 *
 */
public class RPCCall {

	private MessageBase				reqMsg;
	private MessageBase				rspMsg;
	private boolean					sourceWasKnownReachable;
	private List<RPCCallListener>	listeners		= new ArrayList<>(3);
	private ScheduledFuture<?>		timeoutTimer;
	long					sentTime		= -1;
	long					responseTime	= -1;
	private Key						expectedID;
	long					expectedRTT = -1;
	RPCState state = RPCState.UNSENT;
	
	ScheduledExecutorService scheduler;

	public RPCCall (MessageBase msg) {
		assert(msg != null);
		this.reqMsg = msg;
	}
	
	public RPCCall setExpectedID(Key id) {
		expectedID = id;
		return this;
	}
	
	public void builtFromEntry(KBucketEntry e) {
		sourceWasKnownReachable = e.verifiedReachable();
	}
	
	public boolean knownReachableAtCreationTime() {
		return sourceWasKnownReachable;
	}
	
	public RPCCall setExpectedRTT(long rtt) {
		expectedRTT = rtt;
		return this;
	}
	
	public long getExpectedRTT() {
		return expectedRTT;
	}
	
	/**
	 * @throws NullPointerException if no expected id has been specified in advance
	 */
	public boolean matchesExpectedID() {
		return expectedID.equals(rspMsg.getID());
	}
	
	public Key getExpectedID() {
		return expectedID;
	}
	
	/**
	 * when external circumstances indicate that this request is probably stalled and will time out
	 */
	void injectStall() {
		stateTransition(EnumSet.of(RPCState.SENT), RPCState.STALLED);
	}
	
	public void response (MessageBase rsp) {
		if (timeoutTimer != null) {
			timeoutTimer.cancel(false);
		}
		
		rspMsg = rsp;
		
		switch(rsp.getType()) {
			case RSP_MSG:
				stateTransition(EnumSet.of(RPCState.SENT, RPCState.STALLED) , RPCState.RESPONDED);
				break;
			case ERR_MSG:
				DHT.logError("received non-response ["+ rsp +"] in response to request: "+ reqMsg.toString());
				stateTransition(EnumSet.of(RPCState.SENT, RPCState.STALLED) , RPCState.ERROR);
				break;
			default:
				throw new IllegalStateException("should not happen");
		}

	}

	/* (non-Javadoc)
	 * @see lbms.plugins.mldht.kad.RPCCallBase#addListener(lbms.plugins.mldht.kad.RPCCallListener)
	 */
	public RPCCall addListener (RPCCallListener cl) {
		Objects.requireNonNull(cl);
		if(state != RPCState.UNSENT)
			throw new IllegalStateException("can only attach listeners while call is not started yet");
		listeners.add(cl);
		return this;
	}

	/* (non-Javadoc)
	 * @see lbms.plugins.mldht.kad.RPCCallBase#getMessageMethod()
	 */
	public Method getMessageMethod () {
		return reqMsg.getMethod();
	}

	/// Get the request sent
	/* (non-Javadoc)
	 * @see lbms.plugins.mldht.kad.RPCCallBase#getRequest()
	 */
	public MessageBase getRequest () {
		return reqMsg;
	}
	
	public MessageBase getResponse() {
		return rspMsg;
	}
	
	void sent(RPCServer srv) {
		assert(expectedRTT > 0);
		assert(expectedRTT <= DHTConstants.RPC_CALL_TIMEOUT_MAX);
		sentTime = System.currentTimeMillis();
		
		
		stateTransition(EnumSet.of(RPCState.UNSENT), RPCState.SENT);
		
		scheduler = srv.getDHT().getScheduler();
		
		// spread out the stalls by +- 1ms to reduce lock contention
		int smear = ThreadLocalRandom.current().nextInt(-1000, 1000);
		timeoutTimer = scheduler.schedule(this::checkStallOrTimeout, expectedRTT*1000+smear, TimeUnit.MICROSECONDS);
	}
	
	
	void checkStallOrTimeout() {
		synchronized (this)
		{
			if(state != RPCState.SENT && state != RPCState.STALLED)
				return;
			
			long elapsed = System.currentTimeMillis() - sentTime;
			long remaining = DHTConstants.RPC_CALL_TIMEOUT_MAX - elapsed;
			if(remaining > 0)
			{
				stateTransition(EnumSet.of(RPCState.SENT), RPCState.STALLED);
				// re-schedule for failed
				timeoutTimer = scheduler.schedule(this::checkStallOrTimeout, remaining, TimeUnit.MILLISECONDS);
			} else {
				stateTransition(EnumSet.of(RPCState.SENT, RPCState.STALLED), RPCState.TIMEOUT);
			}
		}
	}

	void sendFailed() {
		stateTransition(EnumSet.of(RPCState.UNSENT), RPCState.TIMEOUT);
	}


	private void stateTransition(EnumSet<RPCState> expected, RPCState newState) {
		synchronized (this) {
			RPCState oldState = state;
			
			if(!expected.contains(oldState)) {
				return;
			}
			
			state = newState;

			
			switch(newState) {
				case TIMEOUT:
					DHT.logDebug("RPCCall timed out ID: " + prettyPrint(reqMsg.getMTID()));
					break;
				case ERROR:
				case RESPONDED:
					responseTime = System.currentTimeMillis();
					break;
				case SENT:
					break;
				case STALLED:
					break;
				case UNSENT:
					break;
				default:
					break;
			}
			
			
			for(int i=0;i<listeners.size();i++) {
				RPCCallListener l = listeners.get(i);
				l.stateTransition(this, oldState, newState);
				
				switch(newState) {
					case TIMEOUT:
						l.onTimeout(this);
						break;
					case STALLED:
						l.onStall(this);
						break;
					case RESPONDED:
						l.onResponse(this, rspMsg);

				}

			}


			
		}
	}
	
	/**
	 * @return -1 if there is no response yet or it has timed out. The round trip time in milliseconds otherwise
	 */
	public long getRTT() {
		if(sentTime == -1 || responseTime == -1)
			return -1;
		return responseTime - sentTime;
	}
	
	public long getSentTime()
	{
		return sentTime;
	}
	
	public RPCState state() {
		return state;
	}
	
	public boolean inFlight() {
		return state != RPCState.TIMEOUT && state != RPCState.RESPONDED;
	}

}
