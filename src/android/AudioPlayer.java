/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
*/
package org.apache.cordova.media;

import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.MediaRecorder;
import android.os.Environment;

import org.apache.cordova.LOG;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.LinkedList;

import android.media.AudioFormat;
import android.media.AudioRecord;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * This class implements the audio playback and recording capabilities used by Cordova.
 * It is called by the AudioHandler Cordova class.
 * Only one file can be played or recorded per class instance.
 *
 * Local audio files must reside in one of two places:
 *      android_asset:      file name must start with /android_asset/sound.mp3
 *      sdcard:             file name is just sound.mp3
 */
public class AudioPlayer implements OnCompletionListener, OnPreparedListener, OnErrorListener {

    // AudioPlayer modes
    public enum MODE { NONE, PLAY, RECORD };

    // AudioPlayer states
    public enum STATE { MEDIA_NONE,
                        MEDIA_STARTING,
                        MEDIA_RUNNING,
                        MEDIA_PAUSED,
                        MEDIA_STOPPED,
                        MEDIA_LOADING
                      };

    private static final String LOG_TAG = "AudioPlayer";

    // AudioPlayer message ids
    private static int MEDIA_STATE = 1;
    private static int MEDIA_DURATION = 2;
    private static int MEDIA_POSITION = 3;
    private static int MEDIA_ERROR = 9;

    // Media error codes
    private static int MEDIA_ERR_NONE_ACTIVE    = 0;
    private static int MEDIA_ERR_ABORTED        = 1;
//    private static int MEDIA_ERR_NETWORK        = 2;
//    private static int MEDIA_ERR_DECODE         = 3;
//    private static int MEDIA_ERR_NONE_SUPPORTED = 4;

    private AudioHandler handler;           // The AudioHandler object
    private String id;                      // The id of this player (used to identify Media object in JavaScript)
    private MODE mode = MODE.NONE;          // Playback or Recording mode
    private STATE state = STATE.MEDIA_NONE; // State of recording or playback

    private String audioFile = null;        // File name to play or record to
    private float duration = -1;            // Duration of audio

    private MediaRecorder recorder = null;  // Audio recording object
    private LinkedList<String> tempFiles = null; // Temporary recording file name
    private String tempFile = null;

    private MediaPlayer player = null;      // Audio player object
    private boolean prepareOnly = true;     // playback after file prepare flag
    private int seekOnPrepared = 0;     // seek to this location once media is prepared

    private Thread streamThread;

    // Member variables representing frame data
    private int mSampleRate;
    private int mChannels;
    private int mNumSamples;  // total number of samples per channel in audio file
    private ByteBuffer mDecodedBytes;  // Raw audio data
    private ShortBuffer mDecodedSamples;  // shared buffer with mDecodedBytes.

    // Member variables for hack (making it work with old version, until app just uses the samples).
    private int mNumFrames;
    private int[] mFrameGains;

    // Should be removed when the app will use directly the samples instead of the frames.
    public int getSamplesPerFrame() {
        return 1024;  // just a fixed value here...
    }

    /**
     * Constructor.
     *
     * @param handler           The audio handler object
     * @param id                The id of this audio player
     */
    public AudioPlayer(AudioHandler handler, String id, String file) {
        this.handler = handler;
        this.id = id;
        this.audioFile = file;
        this.tempFiles = new LinkedList<String>();
    }

    private String generateTempFile() {
      String tempFileName = null;
      if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
          tempFileName = Environment.getExternalStorageDirectory().getAbsolutePath() + "/tmprecording-" + System.currentTimeMillis() + ".wav";
      } else {
          tempFileName = "/data/data/" + handler.cordova.getActivity().getPackageName() + "/cache/tmprecording-" + System.currentTimeMillis() + ".wav";
      }
      return tempFileName;
    }

    /**
     * Destroy player and stop audio playing or recording.
     */
    public void destroy() {
        // Stop any play or record
        if (this.player != null) {
            if ((this.state == STATE.MEDIA_RUNNING) || (this.state == STATE.MEDIA_PAUSED)) {
                this.player.stop();
                this.setState(STATE.MEDIA_STOPPED);
            }
            this.player.release();
            this.player = null;
        }
        if (this.recorder != null) {
            this.stopRecording(true);
            this.recorder.release();
            this.recorder = null;
        }
    }

    /**
     * Start recording the specified file.
     *
     * @param file              The name of the file
     */
    public void startRecording(String file) {
        switch (this.mode) {
        case PLAY:
            LOG.d(LOG_TAG, "AudioPlayer Error: Can't record in play mode.");
            sendErrorStatus(MEDIA_ERR_ABORTED);
            break;
        case NONE:

            this.audioFile = file;
            this.tempFile = generateTempFile();

            streamThread = new Thread(new Runnable() {
                @Override
                public void run() {

                    mSampleRate = 8000; //44100;
                    mChannels = 1;  // record mono audio.
                    short[] buffer = new short[1024];  // buffer contains 1 mono frame of 1024 16 bits samples
                    int minBufferSize = AudioRecord.getMinBufferSize(
                            mSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    // make sure minBufferSize can contain at least 1 second of audio (16 bits sample).
                    if (minBufferSize < mSampleRate * 2) {
                        minBufferSize = mSampleRate * 2;
                    }
                    AudioRecord audioRecord = new AudioRecord(
                            MediaRecorder.AudioSource.DEFAULT,
                            mSampleRate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            minBufferSize
                    );

                    // Allocate memory for 20 seconds first. Reallocate later if more is needed.
                    mDecodedBytes = ByteBuffer.allocate(20 * mSampleRate * 2);
                    mDecodedBytes.order(ByteOrder.LITTLE_ENDIAN);
                    mDecodedSamples = mDecodedBytes.asShortBuffer();
                    audioRecord.startRecording();
                    setState(STATE.MEDIA_RUNNING);

                    while (state == STATE.MEDIA_RUNNING) {
                        // check if mDecodedSamples can contain 1024 additional samples.
                        if (mDecodedSamples.remaining() < 1024) {
                            // Try to allocate memory for 10 additional seconds.
                            int newCapacity = mDecodedBytes.capacity() + 10 * mSampleRate * 2;
                            ByteBuffer newDecodedBytes;
                            try {
                                newDecodedBytes = ByteBuffer.allocate(newCapacity);
                            } catch (OutOfMemoryError oome) {
                                break;
                            }
                            int position = mDecodedSamples.position();
                            mDecodedBytes.rewind();
                            newDecodedBytes.put(mDecodedBytes);
                            mDecodedBytes = newDecodedBytes;
                            mDecodedBytes.order(ByteOrder.LITTLE_ENDIAN);
                            mDecodedBytes.rewind();
                            mDecodedSamples = mDecodedBytes.asShortBuffer();
                            mDecodedSamples.position(position);
                        }
                        // TODO(nfaralli): maybe use the read method that takes a direct ByteBuffer argument.
                        audioRecord.read(buffer, 0, buffer.length);
                        mDecodedSamples.put(buffer);
                    }
                    audioRecord.stop();
                    audioRecord.release();
                    mNumSamples = mDecodedSamples.position();
                    mDecodedSamples.rewind();
                    mDecodedBytes.rewind();

                    // Temporary hack to make it work with the old version.
                    mNumFrames = mNumSamples / getSamplesPerFrame();
                    if (mNumSamples % getSamplesPerFrame() != 0) {
                        mNumFrames++;
                    }
                    mFrameGains = new int[mNumFrames];
                    int i, j;
                    int gain, value;
                    for (i = 0; i < mNumFrames; i++) {
                        gain = -1;
                        for (j = 0; j < getSamplesPerFrame(); j++) {
                            if (mDecodedSamples.remaining() > 0) {
                                value = java.lang.Math.abs(mDecodedSamples.get());
                            } else {
                                value = 0;
                            }
                            if (gain < value) {
                                gain = value;
                            }
                        }
                        mFrameGains[i] = (int) Math.sqrt(gain);  // here gain = sqrt(max value of 1st channel)...
                    }
                    mDecodedSamples.rewind();

                    try {
                        writeWAVFile(new File(tempFile), 0, mNumFrames);
                    } catch (IOException e) {
                        e.printStackTrace();
                        sendErrorStatus(MEDIA_ERR_ABORTED);
                    }
                }
            });
            streamThread.start();
            break;
        case RECORD:
            LOG.d(LOG_TAG, "AudioPlayer Error: Already recording.");
            sendErrorStatus(MEDIA_ERR_ABORTED);
        }
    }

    // Method used to swap the left and right channels (needed for stereo WAV files).
    // buffer contains the PCM data: {sample 1 right, sample 1 left, sample 2 right, etc.}
    // The size of a sample is assumed to be 16 bits (for a single channel).
    // When done, buffer will contain {sample 1 left, sample 1 right, sample 2 left, etc.}
    private void swapLeftRightChannels(byte[] buffer) {
        byte left[] = new byte[2];
        byte right[] = new byte[2];
        if (buffer.length % 4 != 0) {  // 2 channels, 2 bytes per sample (for one channel).
            // Invalid buffer size.
            return;
        }
        for (int offset = 0; offset < buffer.length; offset += 4) {
            left[0] = buffer[offset];
            left[1] = buffer[offset + 1];
            right[0] = buffer[offset + 2];
            right[1] = buffer[offset + 3];
            buffer[offset] = right[0];
            buffer[offset + 1] = right[1];
            buffer[offset + 2] = left[0];
            buffer[offset + 3] = left[1];
        }
    }

    // should be removed in the near future...
    public void writeWAVFile(File outputFile, int startFrame, int numFrames)
            throws java.io.IOException {
        float startTime = (float) startFrame * getSamplesPerFrame() / mSampleRate;
        float endTime = (float) (startFrame + numFrames) * getSamplesPerFrame() / mSampleRate;
        writeWAVFile(outputFile, startTime, endTime);
    }

    public void writeWAVFile(File outputFile, float startTime, float endTime)
            throws java.io.IOException {
        int startOffset = (int) (startTime * mSampleRate) * 2 * mChannels;
        int numSamples = (int) ((endTime - startTime) * mSampleRate);

        // Start by writing the RIFF header.
        FileOutputStream outputStream = new FileOutputStream(outputFile);
        WAVHeader wavHeader = new WAVHeader(mSampleRate, mChannels, numSamples);
        outputStream.write(wavHeader.getWAVHeader());

        // Write the samples to the file, 1024 at a time.
        byte buffer[] = new byte[1024 * mChannels * 2];  // Each sample is coded with a short.
        mDecodedBytes.position(startOffset);
        int numBytesLeft = numSamples * mChannels * 2;
        while (numBytesLeft >= buffer.length) {
            if (mDecodedBytes.remaining() < buffer.length) {
                // This should not happen.
                for (int i = mDecodedBytes.remaining(); i < buffer.length; i++) {
                    buffer[i] = 0;  // pad with extra 0s to make a full frame.
                }
                mDecodedBytes.get(buffer, 0, mDecodedBytes.remaining());
            } else {
                mDecodedBytes.get(buffer);
            }
            if (mChannels == 2) {
                swapLeftRightChannels(buffer);
            }
            outputStream.write(buffer);
            numBytesLeft -= buffer.length;
        }
        if (numBytesLeft > 0) {
            if (mDecodedBytes.remaining() < numBytesLeft) {
                // This should not happen.
                for (int i = mDecodedBytes.remaining(); i < numBytesLeft; i++) {
                    buffer[i] = 0;  // pad with extra 0s to make a full frame.
                }
                mDecodedBytes.get(buffer, 0, mDecodedBytes.remaining());
            } else {
                mDecodedBytes.get(buffer, 0, numBytesLeft);
            }
            if (mChannels == 2) {
                swapLeftRightChannels(buffer);
            }
            outputStream.write(buffer, 0, numBytesLeft);
        }
        outputStream.close();
        moveFile(audioFile);
        streamThread.interrupt();
    }

    /**
     * Save temporary recorded file to specified name
     *
     * @param file
     */
    public void moveFile(String file) {
        /* this is a hack to save the file as the specified name */

        if (!file.startsWith("/")) {
            if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                file = Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + file;
            } else {
                file = "/data/data/" + handler.cordova.getActivity().getPackageName() + "/cache/" + file;
            }
        }

        int size = this.tempFiles.size();
        LOG.d(LOG_TAG, "size = " + size);

        // only one file so just copy it
        if (size == 1) {
            String logMsg = "renaming " + this.tempFile + " to " + file;
            LOG.d(LOG_TAG, logMsg);
            File f = new File(this.tempFile);
            if (!f.renameTo(new File(file))) LOG.e(LOG_TAG, "FAILED " + logMsg);
        }
        // more than one file so the user must have pause recording. We'll need to concat files.
        else {
          FileOutputStream outputStream = null;
          try {
              outputStream = new FileOutputStream(new File(file));
              FileInputStream inputStream = null;
              File inputFile = null;
              for (int i = 0; i < size; i++) {
                  try {
                      inputFile = new File(this.tempFiles.get(i));
                      inputStream = new FileInputStream(inputFile);
                      copy(inputStream, outputStream, (i>0));
                  } catch(Exception e) {
                      LOG.e(LOG_TAG, e.getLocalizedMessage(), e);
                  } finally {
                      if (inputStream != null) try {
                          inputStream.close();
                          inputFile.delete();
                          inputFile = null;
                      } catch (Exception e) {
                          LOG.e(LOG_TAG, e.getLocalizedMessage(), e);
                      }
                  }
              }
          } catch(Exception e) {
              e.printStackTrace();
          } finally {
              if (outputStream != null) try {
                  outputStream.close();
              } catch (Exception e) {
                  LOG.e(LOG_TAG, e.getLocalizedMessage(), e);
              }
          }
        }
    }

    private static long copy(InputStream from, OutputStream to, boolean skipHeader)
                throws IOException {
        byte[] buf = new byte[8096];
        long total = 0;
        if (skipHeader) {
            from.skip(6);
        }
        while (true) {
            int r = from.read(buf);
            if (r == -1) {
                break;
            }
            to.write(buf, 0, r);
            total += r;
        }
        return total;
    }

    /**
     * Stop/Pause recording and save to the file specified when recording started.
     */
    public void stopRecording(boolean stop) {
        if (!this.tempFiles.contains(this.tempFile)) {
            this.tempFiles.add(this.tempFile);
        }
        this.setState(STATE.MEDIA_STOPPED);
    }

    // /**
    //  * Stop/Pause recording and save to the file specified when recording started.
    //  */
    // public void stopRecording(boolean stop) {
    //     if (this.recorder != null) {
    //         try{
    //             if (this.state == STATE.MEDIA_RUNNING) {
    //                 this.recorder.stop();
    //             }
    //             this.recorder.reset();
    //             if (!this.tempFiles.contains(this.tempFile)) {
    //                 this.tempFiles.add(this.tempFile);
    //             }
    //             if (stop) {
    //                 LOG.d(LOG_TAG, "stopping recording");
    //                 this.setState(STATE.MEDIA_STOPPED);
    //                 this.moveFile(this.audioFile);
    //             } else {
    //                 LOG.d(LOG_TAG, "pause recording");
    //                 this.setState(STATE.MEDIA_PAUSED);
    //             }
    //         }
    //         catch (Exception e) {
    //             e.printStackTrace();
    //         }
    //     }
    // }

    /**
     * Resume recording and save to the file specified when recording started.
     */
    public void resumeRecording() {
        startRecording(this.audioFile);
    }

    //==========================================================================
    // Playback
    //==========================================================================

    /**
     * Start or resume playing audio file.
     *
     * @param file              The name of the audio file.
     */
    public void startPlaying(String file) {
        if (this.readyPlayer(file) && this.player != null) {
            this.player.start();
            this.setState(STATE.MEDIA_RUNNING);
            this.seekOnPrepared = 0; //insures this is always reset
        } else {
            this.prepareOnly = false;
        }
    }

    /**
     * Seek or jump to a new time in the track.
     */
    public void seekToPlaying(int milliseconds) {
        if (this.readyPlayer(this.audioFile)) {
            if (milliseconds > 0) {
                this.player.seekTo(milliseconds);
            }
            LOG.d(LOG_TAG, "Send a onStatus update for the new seek");
            sendStatusChange(MEDIA_POSITION, null, (milliseconds / 1000.0f));
        }
        else {
            this.seekOnPrepared = milliseconds;
        }
    }

    /**
     * Pause playing.
     */
    public void pausePlaying() {

        // If playing, then pause
        if (this.state == STATE.MEDIA_RUNNING && this.player != null) {
            this.player.pause();
            this.setState(STATE.MEDIA_PAUSED);
        }
        else {
            LOG.d(LOG_TAG, "AudioPlayer Error: pausePlaying() called during invalid state: " + this.state.ordinal());
            sendErrorStatus(MEDIA_ERR_NONE_ACTIVE);
        }
    }

    /**
     * Stop playing the audio file.
     */
    public void stopPlaying() {
        if ((this.state == STATE.MEDIA_RUNNING) || (this.state == STATE.MEDIA_PAUSED)) {
            this.player.pause();
            this.player.seekTo(0);
            LOG.d(LOG_TAG, "stopPlaying is calling stopped");
            this.setState(STATE.MEDIA_STOPPED);
        }
        else {
            LOG.d(LOG_TAG, "AudioPlayer Error: stopPlaying() called during invalid state: " + this.state.ordinal());
            sendErrorStatus(MEDIA_ERR_NONE_ACTIVE);
        }
    }

    /**
     * Resume playing.
     */
    public void resumePlaying() {
    	this.startPlaying(this.audioFile);
    }

    /**
     * Callback to be invoked when playback of a media source has completed.
     *
     * @param player           The MediaPlayer that reached the end of the file
     */
    public void onCompletion(MediaPlayer player) {
        LOG.d(LOG_TAG, "on completion is calling stopped");
        this.setState(STATE.MEDIA_STOPPED);
    }

    /**
     * Get current position of playback.
     *
     * @return                  position in msec or -1 if not playing
     */
    public long getCurrentPosition() {
        if ((this.state == STATE.MEDIA_RUNNING) || (this.state == STATE.MEDIA_PAUSED)) {
            int curPos = this.player.getCurrentPosition();
            sendStatusChange(MEDIA_POSITION, null, (curPos / 1000.0f));
            return curPos;
        }
        else {
            return -1;
        }
    }

    /**
     * Determine if playback file is streaming or local.
     * It is streaming if file name starts with "http://"
     *
     * @param file              The file name
     * @return                  T=streaming, F=local
     */
    public boolean isStreaming(String file) {
        if (file.contains("http://") || file.contains("https://") || file.contains("rtsp://")) {
            return true;
        }
        else {
            return false;
        }
    }

    /**
      * Get the duration of the audio file.
      *
      * @param file             The name of the audio file.
      * @return                 The duration in msec.
      *                             -1=can't be determined
      *                             -2=not allowed
      */
    public float getDuration(String file) {

        // Can't get duration of recording
        if (this.recorder != null) {
            return (-2); // not allowed
        }

        // If audio file already loaded and started, then return duration
        if (this.player != null) {
            return this.duration;
        }

        // If no player yet, then create one
        else {
            this.prepareOnly = true;
            this.startPlaying(file);

            // This will only return value for local, since streaming
            // file hasn't been read yet.
            return this.duration;
        }
    }

    /**
     * Callback to be invoked when the media source is ready for playback.
     *
     * @param player           The MediaPlayer that is ready for playback
     */
    public void onPrepared(MediaPlayer player) {
        // Listen for playback completion
        this.player.setOnCompletionListener(this);
        // seek to any location received while not prepared
        this.seekToPlaying(this.seekOnPrepared);
        // If start playing after prepared
        if (!this.prepareOnly) {
            this.player.start();
            this.setState(STATE.MEDIA_RUNNING);
            this.seekOnPrepared = 0; //reset only when played
        } else {
            this.setState(STATE.MEDIA_STARTING);
        }
        // Save off duration
        this.duration = getDurationInSeconds();
        // reset prepare only flag
        this.prepareOnly = true;

        // Send status notification to JavaScript
        sendStatusChange(MEDIA_DURATION, null, this.duration);
    }

    /**
     * By default Android returns the length of audio in mills but we want seconds
     *
     * @return length of clip in seconds
     */
    private float getDurationInSeconds() {
        return (this.player.getDuration() / 1000.0f);
    }

    /**
     * Callback to be invoked when there has been an error during an asynchronous operation
     *  (other errors will throw exceptions at method call time).
     *
     * @param player           the MediaPlayer the error pertains to
     * @param arg1              the type of error that has occurred: (MEDIA_ERROR_UNKNOWN, MEDIA_ERROR_SERVER_DIED)
     * @param arg2              an extra code, specific to the error.
     */
    public boolean onError(MediaPlayer player, int arg1, int arg2) {
        LOG.d(LOG_TAG, "AudioPlayer.onError(" + arg1 + ", " + arg2 + ")");

        // we don't want to send success callback
        // so we don't call setState() here
        this.state = STATE.MEDIA_STOPPED;
        this.destroy();
        // Send error notification to JavaScript
        sendErrorStatus(arg1);

        return false;
    }

    /**
     * Set the state and send it to JavaScript.
     *
     * @param state
     */
    private void setState(STATE state) {
        if (this.state != state) {
            sendStatusChange(MEDIA_STATE, null, (float)state.ordinal());
        }
        this.state = state;
    }

    /**
     * Set the mode and send it to JavaScript.
     *
     * @param mode
     */
    private void setMode(MODE mode) {
        if (this.mode != mode) {
            //mode is not part of the expected behavior, so no notification
            //this.handler.webView.sendJavascript("cordova.require('cordova-plugin-media.Media').onStatus('" + this.id + "', " + MEDIA_STATE + ", " + mode + ");");
        }
        this.mode = mode;
    }

    /**
     * Get the audio state.
     *
     * @return int
     */
    public int getState() {
        return this.state.ordinal();
    }

    /**
     * Set the volume for audio player
     *
     * @param volume
     */
    public void setVolume(float volume) {
        if (this.player != null) {
            this.player.setVolume(volume, volume);
        } else {
            LOG.d(LOG_TAG, "AudioPlayer Error: Cannot set volume until the audio file is initialized.");
            sendErrorStatus(MEDIA_ERR_NONE_ACTIVE);
        }
    }

    /**
     * attempts to put the player in play mode
     * @return true if in playmode, false otherwise
     */
    private boolean playMode() {
        switch(this.mode) {
        case NONE:
            this.setMode(MODE.PLAY);
            break;
        case PLAY:
            break;
        case RECORD:
            LOG.d(LOG_TAG, "AudioPlayer Error: Can't play in record mode.");
            sendErrorStatus(MEDIA_ERR_ABORTED);
            return false; //player is not ready
        }
        return true;
    }

    /**
     * attempts to initialize the media player for playback
     * @param file the file to play
     * @return false if player not ready, reports if in wrong mode or state
     */
    private boolean readyPlayer(String file) {
        if (playMode()) {
            switch (this.state) {
                case MEDIA_NONE:
                    if (this.player == null) {
                        this.player = new MediaPlayer();
                        this.player.setOnErrorListener(this);
                    }
                    try {
                        this.loadAudioFile(file);
                    } catch (Exception e) {
                        sendErrorStatus(MEDIA_ERR_ABORTED);
                    }
                    return false;
                case MEDIA_LOADING:
                    //cordova js is not aware of MEDIA_LOADING, so we send MEDIA_STARTING instead
                    LOG.d(LOG_TAG, "AudioPlayer Loading: startPlaying() called during media preparation: " + STATE.MEDIA_STARTING.ordinal());
                    this.prepareOnly = false;
                    return false;
                case MEDIA_STARTING:
                case MEDIA_RUNNING:
                case MEDIA_PAUSED:
                    return true;
                case MEDIA_STOPPED:
                    //if we are readying the same file
                    if (file!=null && this.audioFile.compareTo(file) == 0) {
                        //maybe it was recording?
                        if (player == null) {
                            this.player = new MediaPlayer();
                            this.player.setOnErrorListener(this);
                            this.prepareOnly = false;

                            try {
                                this.loadAudioFile(file);
                            } catch (Exception e) {
                                sendErrorStatus(MEDIA_ERR_ABORTED);
                            }
                            return false;//we´re not ready yet
                        }
                        else {
                           //reset the audio file
                            player.seekTo(0);
                            player.pause();
                            return true;
                        }
                    } else {
                        //reset the player
                        this.player.reset();
                        try {
                            this.loadAudioFile(file);
                        } catch (Exception e) {
                            sendErrorStatus(MEDIA_ERR_ABORTED);
                        }
                        //if we had to prepare the file, we won't be in the correct state for playback
                        return false;
                    }
                default:
                    LOG.d(LOG_TAG, "AudioPlayer Error: startPlaying() called during invalid state: " + this.state);
                    sendErrorStatus(MEDIA_ERR_ABORTED);
            }
        }
        return false;
    }

    /**
     * load audio file
     * @throws IOException
     * @throws IllegalStateException
     * @throws SecurityException
     * @throws IllegalArgumentException
     */
    private void loadAudioFile(String file) throws IllegalArgumentException, SecurityException, IllegalStateException, IOException {
        if (this.isStreaming(file)) {
            this.player.setDataSource(file);
            this.player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            //if it's a streaming file, play mode is implied
            this.setMode(MODE.PLAY);
            this.setState(STATE.MEDIA_STARTING);
            this.player.setOnPreparedListener(this);
            this.player.prepareAsync();
        }
        else {
            if (file.startsWith("/android_asset/")) {
                String f = file.substring(15);
                android.content.res.AssetFileDescriptor fd = this.handler.cordova.getActivity().getAssets().openFd(f);
                this.player.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            }
            else {
                File fp = new File(file);
                if (fp.exists()) {
                    FileInputStream fileInputStream = new FileInputStream(file);
                    this.player.setDataSource(fileInputStream.getFD());
                    fileInputStream.close();
                }
                else {
                    this.player.setDataSource(Environment.getExternalStorageDirectory().getPath() + "/" + file);
                }
            }
                this.setState(STATE.MEDIA_STARTING);
                this.player.setOnPreparedListener(this);
                this.player.prepare();

                // Get duration
                this.duration = getDurationInSeconds();
            }
    }

    private void sendErrorStatus(int errorCode) {
        sendStatusChange(MEDIA_ERROR, errorCode, null);
    }

    private void sendStatusChange(int messageType, Integer additionalCode, Float value) {

        if (additionalCode != null && value != null) {
            throw new IllegalArgumentException("Only one of additionalCode or value can be specified, not both");
        }

        JSONObject statusDetails = new JSONObject();
        try {
            statusDetails.put("id", this.id);
            statusDetails.put("msgType", messageType);
            if (additionalCode != null) {
                JSONObject code = new JSONObject();
                code.put("code", additionalCode.intValue());
                statusDetails.put("value", code);
            }
            else if (value != null) {
                statusDetails.put("value", value.floatValue());
            }
        } catch (JSONException e) {
            LOG.e(LOG_TAG, "Failed to create status details", e);
        }

        this.handler.sendEventMessage("status", statusDetails);
    }

    /**
     * Get current amplitude of recording.
     *
     * @return amplitude or 0 if not recording
     */
    public float getCurrentAmplitude() {
        if (this.recorder != null) {
            try{
                if (this.state == STATE.MEDIA_RUNNING) {
                    return (float) this.recorder.getMaxAmplitude() / 32762;
                }
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }
        return 0;
    }

    private class WAVHeader {
        private byte[] mHeader;          // the complete header.
        private int mSampleRate;         // sampling frequency in Hz (e.g. 44100).
        private int mChannels;           // number of channels.
        private int mNumSamples;         // total number of samples per channel.
        private int mNumBytesPerSample;  // number of bytes per sample, all channels included.

        public WAVHeader(int sampleRate, int numChannels, int numSamples) {
            mSampleRate = sampleRate;
            mChannels = numChannels;
            mNumSamples = numSamples;
            mNumBytesPerSample = 2 * mChannels;  // assuming 2 bytes per sample (for 1 channel)
            mHeader = null;
            setHeader();
        }

        public byte[] getWAVHeader() {
            return mHeader;
        }

        public String toString() {
            String str = "";
            if (mHeader == null) {
                return str;
            }
            int num_32bits_per_lines = 8;
            int count = 0;
            for (byte b : mHeader) {
                boolean break_line = count > 0 && count % (num_32bits_per_lines * 4) == 0;
                boolean insert_space = count > 0 && count % 4 == 0 && !break_line;
                if (break_line) {
                    str += '\n';
                }
                if (insert_space) {
                    str += ' ';
                }
                str += String.format("%02X", b);
                count++;
            }

            return str;
        }

        private void setHeader() {
            byte[] header = new byte[46];
            int offset = 0;
            int size;

            // set the RIFF chunk
            System.arraycopy(new byte[]{'R', 'I', 'F', 'F'}, 0, header, offset, 4);
            offset += 4;
            size = 36 + mNumSamples * mNumBytesPerSample;
            header[offset++] = (byte) (size & 0xFF);
            header[offset++] = (byte) ((size >> 8) & 0xFF);
            header[offset++] = (byte) ((size >> 16) & 0xFF);
            header[offset++] = (byte) ((size >> 24) & 0xFF);
            System.arraycopy(new byte[]{'W', 'A', 'V', 'E'}, 0, header, offset, 4);
            offset += 4;

            // set the fmt chunk
            System.arraycopy(new byte[]{'f', 'm', 't', ' '}, 0, header, offset, 4);
            offset += 4;
            System.arraycopy(new byte[]{0x10, 0, 0, 0}, 0, header, offset, 4);  // chunk size = 16
            offset += 4;
            System.arraycopy(new byte[]{1, 0}, 0, header, offset, 2);  // format = 1 for PCM
            offset += 2;
            header[offset++] = (byte) (mChannels & 0xFF);
            header[offset++] = (byte) ((mChannels >> 8) & 0xFF);
            header[offset++] = (byte) (mSampleRate & 0xFF);
            header[offset++] = (byte) ((mSampleRate >> 8) & 0xFF);
            header[offset++] = (byte) ((mSampleRate >> 16) & 0xFF);
            header[offset++] = (byte) ((mSampleRate >> 24) & 0xFF);
            int byteRate = mSampleRate * mNumBytesPerSample;
            header[offset++] = (byte) (byteRate & 0xFF);
            header[offset++] = (byte) ((byteRate >> 8) & 0xFF);
            header[offset++] = (byte) ((byteRate >> 16) & 0xFF);
            header[offset++] = (byte) ((byteRate >> 24) & 0xFF);
            header[offset++] = (byte) (mNumBytesPerSample & 0xFF);
            header[offset++] = (byte) ((mNumBytesPerSample >> 8) & 0xFF);
            System.arraycopy(new byte[]{0x10, 0}, 0, header, offset, 2);
            offset += 2;

            // set the beginning of the data chunk
            System.arraycopy(new byte[]{'d', 'a', 't', 'a'}, 0, header, offset, 4);
            offset += 4;
            size = mNumSamples * mNumBytesPerSample;
            header[offset++] = (byte) (size & 0xFF);
            header[offset++] = (byte) ((size >> 8) & 0xFF);
            header[offset++] = (byte) ((size >> 16) & 0xFF);
            header[offset++] = (byte) ((size >> 24) & 0xFF);

            mHeader = header;
        }
    }
}
