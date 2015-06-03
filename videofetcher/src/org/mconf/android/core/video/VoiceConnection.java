package org.mconf.android.core.video;

import java.util.Map;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.util.Log;

import com.flazr.rtmp.LoopedReader;
import com.flazr.rtmp.RtmpDecoder;
import com.flazr.rtmp.RtmpEncoder;
import com.flazr.rtmp.RtmpMessage;
import com.flazr.rtmp.RtmpPublisher;
import com.flazr.rtmp.RtmpReader;
import com.flazr.rtmp.client.ClientHandshakeHandler;
import com.flazr.rtmp.client.ClientOptions;
import com.flazr.rtmp.message.Audio;
import com.flazr.rtmp.message.Command;
import com.flazr.rtmp.message.CommandAmf0;
import com.flazr.rtmp.message.Control;
import com.flazr.rtmp.message.MessageType;

public abstract class VoiceConnection extends RtmpConnection {

    private static final Logger log = LoggerFactory.getLogger(VoiceConnection.class);
    private String publishName;
    private String playName;
    @SuppressWarnings("unused")
    private String codec;
    private int playStreamId = -1;
    private int publishStreamId = -1;

    public VoiceConnection(ClientOptions options) {
            super(options);
    }
    
    @Override
    protected ChannelPipelineFactory pipelineFactory() {
            return new ChannelPipelineFactory() {
                    @Override
                    public ChannelPipeline getPipeline() throws Exception {
                    final ChannelPipeline pipeline = Channels.pipeline();
                    pipeline.addLast("handshaker", new ClientHandshakeHandler(options));
                    pipeline.addLast("decoder", new RtmpDecoder());
                    pipeline.addLast("encoder", new RtmpEncoder());
                    pipeline.addLast("handler", VoiceConnection.this);
                    return pipeline;
                    }
            };
    }

    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
    /*
     * https://github.com/bigbluebutton/bigbluebutton/blob/master/bigbluebutton-client/src/org/bigbluebutton/modules/phone/managers/ConnectionManager.as#L78
     * netConnection.connect(uri, externUID, username);
     */
    /*JoinedMeeting meeting = context.getJoinService().getJoinedMeeting();
            options.setArgs(meeting.getExternUserID(), context.getMyUserId() + "-" + meeting.getFullname());*/
            Command connect = Command.connect(options);
    writeCommandExpectingResult(e.getChannel(), connect);
    }
    
    @Override
    public void channelDisconnected(ChannelHandlerContext ctx,
                    ChannelStateEvent e) throws Exception {
            super.channelDisconnected(ctx, e);
            log.debug("Rtmp Channel Disconnected");
    }

@SuppressWarnings("unchecked")
    public String connectGetCode(Command command) {
        return ((Map<String, Object>) command.getArg(0)).get("code").toString();
}

// netConnection.call("voiceconf.call", null, "default", username, dialStr);
public void call(Channel channel) {
        //Command command = new CommandAmf0("voiceconf.call", null,"default");
        //writeCommandExpectingResult(channel, command);
}

    // https://github.com/bigbluebutton/bigbluebutton/blob/master/bigbluebutton-client/src/org/bigbluebutton/modules/phone/managers/ConnectionManager.as#L149
protected boolean onCall(String resultFor, Command command) {
        //if (resultFor.equals("voiceconf.call")) {
                log.debug(command.toString());
                return true;
       // } else
               // return false;
}

public void hangup(Channel channel) {
        Command command = new CommandAmf0("voiceconf.hangup", null, 
                        "default");
        writeCommandExpectingResult(channel, command);
}

@Override
protected void onMultimedia(Channel channel, RtmpMessage message) {
        // TODO Auto-generated method stub
        super.onMultimedia(channel, message);
        
}

@Override
protected void onCommandResult(Channel channel, Command command,
                String resultFor) {
    log.info("result for method call: {}", resultFor);
    if(resultFor.equals("connect")) {
            String code = connectGetCode(command);
            if (code.equals("NetConnection.Connect.Success")) {
                    call(channel);
            } else {
                    log.error("method connect result in {}, quitting", code);
                    log.debug("connect response: {}", command.toString());
                    channel.close();
            }
            return;
    } else if(resultFor.equals("createStream")) {

        if (playStreamId == -1) {
            playStreamId = ((Double) command.getArg(0)).intValue();
            log.debug("playStreamId to use: {}", playStreamId);
            ClientOptions newOptions = new ClientOptions();
            newOptions.setStreamName(playName);
            channel.write(Command.play(playStreamId, newOptions));
            channel.write(Control.setBuffer(playStreamId, 0));
            return;

            } else if (publishStreamId == -1) {
            publishStreamId = ((Double) command.getArg(0)).intValue();
            log.debug("publishStreamId to use: {}", publishStreamId);

            ClientOptions newOptions = new ClientOptions();
            newOptions.setStreamName(publishName);
            newOptions.publishLive();

            if (isPublishEnabled()) {
                RtmpReader reader;
                if(options.getFileToPublish() != null) {
                    reader = RtmpPublisher.getReader(options.getFileToPublish());
                } else {
                    reader = options.getReaderToPublish();
                }
                if(options.getLoop() > 1) {
                    reader = new LoopedReader(reader, options.getLoop());
                }
                publisher = new RtmpPublisher(reader, publishStreamId, options.getBuffer(), false, false) {
                    @Override protected RtmpMessage[] getStopMessages(long timePosition) {
                        return new RtmpMessage[]{Command.unpublish(publishStreamId)};
                    }
                };
                newOptions.setLoop(options.getLoop());
                newOptions.setReaderToPublish(options.getReaderToPublish());
            }

            channel.write(Command.publish(publishStreamId, holdChannel(publishStreamId), newOptions));
            return;
            }

    } else if (onCall(resultFor, command)) {
            return;
    } else {
            log.info("ignoring result: {}", command);
    }
}

@Override
protected void onCommandCustom(Channel channel, Command command, String name) {
        if (name.equals("successfullyJoinedVoiceConferenceCallback")) {
            onSuccessfullyJoined(command);
            writeCommandExpectingResult(channel, Command.createStream());
            writeCommandExpectingResult(channel, Command.createStream());
    } else if (name.equals("disconnectedFromJoinVoiceConferenceCallback")) {
            onDisconnectedFromJoin(command);
            channel.close();
    } else if (name.equals("failedToJoinVoiceConferenceCallback")) {
            onFailedToJoin(command);
            channel.close();
    }
}

    private boolean isPublishEnabled() {
            return options.getReaderToPublish() != null;
    }

    private void onFailedToJoin(Command command) {
            // TODO Auto-generated method stub
    }

    /*
     * 14:42:18,175 [New I/O client worker #2-1] DEBUG [com.flazr.amf.Amf0Value] - << [STRING disconnectedFromJoinVoiceConferenceCallback]
     * 14:42:18,175 [New I/O client worker #2-1] DEBUG [com.flazr.amf.Amf0Value] - << [NUMBER 4.0]
     * 14:42:18,175 [New I/O client worker #2-1] DEBUG [com.flazr.amf.Amf0Value] - << [NULL null]
     * 14:42:18,176 [New I/O client worker #2-1] DEBUG [com.flazr.amf.Amf0Value] - << [STRING onUaCallClosed]
     * 14:42:18,176 [New I/O client worker #2-1] DEBUG [org.mconf.bbb.phone.VoiceConnection] - server command: disconnectedFromJoinVoiceConferenceCallback
     */
    private void onDisconnectedFromJoin(Command command) {
            @SuppressWarnings("unused")
            String message = (String) command.getArg(0);
    }

    /*
     * 14:27:38,282 [New I/O client worker #2-1] DEBUG [com.flazr.amf.Amf0Value] - << [STRING successfullyJoinedVoiceConferenceCallback]
     * 14:27:38,282 [New I/O client worker #2-1] DEBUG [com.flazr.amf.Amf0Value] - << [NUMBER 3.0]
     * 14:27:38,282 [New I/O client worker #2-1] DEBUG [com.flazr.amf.Amf0Value] - << [NULL null]
     * 14:27:38,282 [New I/O client worker #2-1] DEBUG [com.flazr.amf.Amf0Value] - << [STRING microphone_1322327135253]
     * 14:27:38,282 [New I/O client worker #2-1] DEBUG [com.flazr.amf.Amf0Value] - << [STRING speaker_1322327135251]
     * 14:27:38,282 [New I/O client worker #2-1] DEBUG [com.flazr.amf.Amf0Value] - << [STRING SPEEX]
     * 14:27:38,282 [New I/O client worker #2-1] DEBUG [org.mconf.bbb.phone.VoiceConnection] - server command: successfullyJoinedVoiceConferenceCallback
     */
    private void onSuccessfullyJoined(Command command) {
            publishName = (String) command.getArg(0);
            playName = (String) command.getArg(1);
            codec = (String) command.getArg(2);
    }
    
    abstract protected void onAudio(Audio audio);

}