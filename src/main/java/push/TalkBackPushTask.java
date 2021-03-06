package push;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.*;
import server.BusinessManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_ADPCM_G726LE;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_PCM_S16LE;

@Slf4j
public class TalkBackPushTask extends Thread {

    private volatile boolean stop = false;

    private FFmpegFrameGrabber audioGrabber;
    private FFmpegFrameRecorder recorder;

    private PipedOutputStream apos;
    private PipedInputStream apis;

    private ByteArrayOutputStream abos = new ByteArrayOutputStream();

    TalkBackPushTask() throws IOException {

        apos = new PipedOutputStream();
        apis = new PipedInputStream(65536);
        apis.connect(apos);
    }

    @Override
    public void run() {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try {
            audioGrabber = new FFmpegFrameGrabber(apis);
            audioGrabber.setFormat("g726le");
            audioGrabber.setSampleRate(8000);     // formal
            //audioGrabber.setSampleFormat(AV_SAMPLE_FMT_S16);
            audioGrabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
            audioGrabber.setAudioBitrate(32000);
            audioGrabber.setAudioChannels(1);
            audioGrabber.setAudioCodec(AV_CODEC_ID_ADPCM_G726LE);
            audioGrabber.start();

            recorder = new FFmpegFrameRecorder(baos, 1);
            recorder.setAudioCodec(AV_CODEC_ID_PCM_S16LE);  // 0x15000 + 2
            recorder.setFormat("s16le");
            recorder.start();

            while (!stop && !isInterrupted()) {

                Frame audioFrame = audioGrabber.grabSamples();

                if(audioFrame != null) {
                    recorder.recordSamples(audioFrame.samples);
                    baos.flush();
                    byte[] data = baos.toByteArray();
                    log.info("data {}", data);
                    log.info("data size {}", data.length);
                    // 发送到 web
                    BusinessManager.getInstance().get("15153139702").channel().writeAndFlush(
                            new BinaryWebSocketFrame(Unpooled.copiedBuffer(data)));
                    baos.reset();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        log.info("close talkBack out");
    }

    public void shutdown() throws FrameGrabber.Exception, FrameRecorder.Exception {
        audioGrabber.stop();
        recorder.stop();
        recorder.release();
        stop = true;
        interrupt();
    }

    public void flushAudio() throws IOException {
        abos.flush();
        apos.write(abos.toByteArray());
        apos.flush();
        abos.reset();
    }

    public void writeAudio(byte[] dataBody) throws IOException {
        abos.write(dataBody);
    }
}
