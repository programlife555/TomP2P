package net.tomp2p.relay.android;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.tomp2p.connection.PeerConnection;
import net.tomp2p.connection.Responder;
import net.tomp2p.futures.FutureDone;
import net.tomp2p.message.Buffer;
import net.tomp2p.message.Message;
import net.tomp2p.message.Message.Type;
import net.tomp2p.p2p.Peer;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.relay.BaseRelayForwarderRPC;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.android.gcm.server.Result;
import com.google.android.gcm.server.Sender;

/**
 * Manages the mapping between a peer address and the registration id. The registration id is sent by the
 * mobile device when the relay is set up.
 * 
 * @author Nico Rutishauser
 *
 */
public class AndroidForwarderRPC extends BaseRelayForwarderRPC implements BufferFullListener {

	private static final Logger LOG = LoggerFactory.getLogger(AndroidForwarderRPC.class);

	private final AndroidRelayConfiguration config;
	private final Sender sender;
	private String registrationId;
	private final MessageBuffer buffer;
	private final List<Buffer> readyToSend;

	
	public AndroidForwarderRPC(Peer peer, PeerConnection peerConnection, AndroidRelayConfiguration config, String registrationId) {
		super(peer, peerConnection);
		this.config = config;
		this.registrationId = registrationId;
		this.sender = new Sender(config.gcmAuthenticationToken());
		this.buffer = new MessageBuffer(config.bufferCountLimit(), config.bufferSizeLimit(), config.bufferAgeLimit(), this);
		this.readyToSend = Collections.synchronizedList(new ArrayList<Buffer>());
				
		// TODO init some listener to detect when the relay is not reachable anymore
	}

	@Override
	public boolean peerFound(PeerAddress remotePeer, PeerAddress referrer, PeerConnection peerConnection) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public FutureDone<Message> forwardToUnreachable(Message message) {
		final FutureDone<Message> futureDone = new FutureDone<Message>();
		final Message response = createResponseMessage(message, Type.OK);
		response.recipient(message.sender());
		response.sender(unreachablePeerAddress());

		try {
			buffer.addMessage(message);
		} catch (Exception e) {
			LOG.error("Cannot encode the message", e);
			return new FutureDone<Message>().failed(e);
		}

		// TODO create temporal OK message
		return futureDone.done(response);
	}

	@Override
	protected void handlePing(Message message, Responder responder, PeerAddress sender) {
		// TODO Check if the mobile device is still alive and answer appropriately
	}

	/**
	 * Tickle the device through Google Cloud Messaging
	 */
	private FutureDone<Void> sendTickleMessage() {
		// the collapse key is the relay's peerId
		final com.google.android.gcm.server.Message tickleMessage = new com.google.android.gcm.server.Message.Builder().collapseKey(relayPeerId().toString()).build();
		final FutureDone<Void> future = new FutureDone<Void>();
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					LOG.debug("Send GCM message to the device {}", registrationId);
					Result result = sender.send(tickleMessage, registrationId, config.gcmSendRetries());
					if(result.getMessageId() == null) {
						LOG.error("Could not send the tickle messge. Reason: {}", result.getErrorCodeName());
						future.failed("Cannot send message over GCM. Reason: " + result.getErrorCodeName());
					} else if(result.getCanonicalRegistrationId() != null) {
						LOG.debug("Update the registration id {} to canonical name {}", registrationId, result.getCanonicalRegistrationId());
						registrationId = result.getCanonicalRegistrationId();
						future.done();
					} else {
						LOG.debug("Successfully sent the message over GCM");
						future.done();
					}
				} catch (IOException e) {
					LOG.error("Cannot send tickle message to device {}", registrationId, e);
					future.failed(e);
				}
			}
		});
		
		return future;
	}

	@Override
	public void bufferFull(List<Buffer> buffer) {
		synchronized (readyToSend) {
			readyToSend.addAll(buffer);
		}
		
		sendTickleMessage();
	}
	
	/**
	 * Retrieves the messages that are ready to send. Ready to send means that they have been buffered and the
	 * Android device has already been notified.
	 * @return
	 */
	public List<Buffer> getReadyToSendBuffer() {
		List<Buffer> copy;
		synchronized (readyToSend) {
			copy = new ArrayList<Buffer>(readyToSend);
			readyToSend.clear();
		}
		return copy;
	}
}
