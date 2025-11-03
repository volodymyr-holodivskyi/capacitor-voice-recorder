package com.tchvu3.capacitorvoicerecorder;

import android.Manifest;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

@CapacitorPlugin(
    name = "VoiceRecorder",
    permissions = { @Permission(alias = VoiceRecorder.RECORD_AUDIO_ALIAS, strings = { Manifest.permission.RECORD_AUDIO }) }
)
public class VoiceRecorder extends Plugin {

    static final String RECORD_AUDIO_ALIAS = "voice recording";
    private CustomMediaRecorder mediaRecorder;

	private int silenceThreshold = 2000; // Amplitude threshold (tune for mic sensitivity)
	private long silenceDuration = 3000; // 3 seconds of silence
	private long lastVoiceTime;

	private final Handler silenceHandler = new Handler(Looper.getMainLooper());
	private final Runnable silenceCheckRunnable = new Runnable() {
		@Override
		public void run() {
			try {
				if (mediaRecorder == null) return;

				int amplitude = mediaRecorder.getMaxAmplitude();
				long currentTime = System.currentTimeMillis();

				if (amplitude > silenceThreshold) {
					lastVoiceTime = currentTime; // sound detected, reset timer
				}

				if (currentTime - lastVoiceTime > silenceDuration) {
					Log.d("VoiceRecorder", "Silence detected. Stopping recording...");
					stopRecordingOnSilence();
					return;
				}

				silenceHandler.postDelayed(this, 300); // check every 300ms
			} catch (Exception e) {
				Log.e("VoiceRecorder", "Error checking silence", e);
			}
		}
	};

    @PluginMethod
    public void canDeviceVoiceRecord(PluginCall call) {
        if (CustomMediaRecorder.canPhoneCreateMediaRecorder(getContext())) {
            call.resolve(ResponseGenerator.successResponse());
        } else {
            call.resolve(ResponseGenerator.failResponse());
        }
    }

    @PluginMethod
    public void requestAudioRecordingPermission(PluginCall call) {
        if (doesUserGaveAudioRecordingPermission()) {
            call.resolve(ResponseGenerator.successResponse());
        } else {
            requestPermissionForAlias(RECORD_AUDIO_ALIAS, call, "recordAudioPermissionCallback");
        }
    }

    @PermissionCallback
    private void recordAudioPermissionCallback(PluginCall call) {
        this.hasAudioRecordingPermission(call);
    }

    @PluginMethod
    public void hasAudioRecordingPermission(PluginCall call) {
        call.resolve(ResponseGenerator.fromBoolean(doesUserGaveAudioRecordingPermission()));
    }

    @PluginMethod
    public void startRecording(PluginCall call) {
        if (!CustomMediaRecorder.canPhoneCreateMediaRecorder(getContext())) {
            call.reject(Messages.CANNOT_RECORD_ON_THIS_PHONE);
            return;
        }

        if (!doesUserGaveAudioRecordingPermission()) {
            call.reject(Messages.MISSING_PERMISSION);
            return;
        }

        if (this.isMicrophoneOccupied()) {
            call.reject(Messages.MICROPHONE_BEING_USED);
            return;
        }

        if (mediaRecorder != null) {
            call.reject(Messages.ALREADY_RECORDING);
            return;
        }

        try {
            String directory = call.getString("directory");
            String subDirectory = call.getString("subDirectory");
			Boolean stopOnSilence = call.getBoolean("stopOnSilence", false);
            RecordOptions options = new RecordOptions(directory, subDirectory, stopOnSilence);
            mediaRecorder = new CustomMediaRecorder(getContext(), options);
            mediaRecorder.startRecording();
			lastVoiceTime = System.currentTimeMillis();
			if (options.getStopOnSilence()) {
				silenceHandler.post(silenceCheckRunnable);
			}
            call.resolve(ResponseGenerator.successResponse());
        } catch (Exception exp) {
            mediaRecorder = null;
            call.reject(Messages.FAILED_TO_RECORD, exp);
        }
    }

    @PluginMethod
    public void stopRecording(PluginCall call) {
        if (mediaRecorder == null) {
            call.reject(Messages.RECORDING_HAS_NOT_STARTED);
            return;
        }

        try {
            mediaRecorder.stopRecording();
            File recordedFile = mediaRecorder.getOutputFile();
            RecordOptions options = mediaRecorder.getRecordOptions();

            String recordDataBase64 = null;
            String uri = null;
            if (options.getDirectory() != null) {
                uri = Uri.fromFile(recordedFile).toString();
            } else {
                recordDataBase64 = readRecordedFileAsBase64(recordedFile);
            }

            RecordData recordData = new RecordData(
                recordDataBase64,
                getMsDurationOfAudioFile(recordedFile.getAbsolutePath()),
                "audio/aac",
                uri
            );
			if (options.getStopOnSilence()) {
				silenceHandler.removeCallbacksAndMessages(null);
			}
            if ((recordDataBase64 == null && uri == null) || recordData.getMsDuration() < 0) {
                call.reject(Messages.EMPTY_RECORDING);
            } else {
                call.resolve(ResponseGenerator.dataResponse(recordData.toJSObject()));
            }
        } catch (Exception exp) {
            call.reject(Messages.FAILED_TO_FETCH_RECORDING, exp);
        } finally {
            RecordOptions options = mediaRecorder.getRecordOptions();
            if (options.getDirectory() == null) {
                mediaRecorder.deleteOutputFile();
            }

            mediaRecorder = null;
        }
    }

    @PluginMethod
    public void pauseRecording(PluginCall call) {
        if (mediaRecorder == null) {
            call.reject(Messages.RECORDING_HAS_NOT_STARTED);
            return;
        }
        try {
            call.resolve(ResponseGenerator.fromBoolean(mediaRecorder.pauseRecording()));
        } catch (NotSupportedOsVersion exception) {
            call.reject(Messages.NOT_SUPPORTED_OS_VERSION);
        }
    }

    @PluginMethod
    public void resumeRecording(PluginCall call) {
        if (mediaRecorder == null) {
            call.reject(Messages.RECORDING_HAS_NOT_STARTED);
            return;
        }
        try {
            call.resolve(ResponseGenerator.fromBoolean(mediaRecorder.resumeRecording()));
        } catch (NotSupportedOsVersion exception) {
            call.reject(Messages.NOT_SUPPORTED_OS_VERSION);
        }
    }

    @PluginMethod
    public void getCurrentStatus(PluginCall call) {
        if (mediaRecorder == null) {
            call.resolve(ResponseGenerator.statusResponse(CurrentRecordingStatus.NONE));
        } else {
            call.resolve(ResponseGenerator.statusResponse(mediaRecorder.getCurrentStatus()));
        }
    }

	private void stopRecordingOnSilence() {
		try {
			mediaRecorder.stopRecording();
			File recordedFile = mediaRecorder.getOutputFile();
			RecordOptions options = mediaRecorder.getRecordOptions();

			String recordDataBase64 = null;
			String uri = null;
			if (options.getDirectory() != null) {
				uri = Uri.fromFile(recordedFile).toString();
			} else {
				recordDataBase64 = readRecordedFileAsBase64(recordedFile);
			}

			RecordData recordData = new RecordData(
				recordDataBase64,
				getMsDurationOfAudioFile(recordedFile.getAbsolutePath()),
				"audio/aac",
				uri
			);
			if (options.getStopOnSilence()) {
				silenceHandler.removeCallbacksAndMessages(null);
			}
			if ((recordDataBase64 == null && uri == null) || recordData.getMsDuration() < 0) {
				//call.reject(Messages.EMPTY_RECORDING);
			} else {
				//call.resolve(ResponseGenerator.dataResponse(recordData.toJSObject()));
				notifyListeners("onSilence", ResponseGenerator.dataResponse(recordData.toJSObject()));
			}
		} catch (Exception exp) {
			//call.reject(Messages.FAILED_TO_FETCH_RECORDING, exp);
		} finally {
			RecordOptions options = mediaRecorder.getRecordOptions();
			if (options.getDirectory() == null) {
				mediaRecorder.deleteOutputFile();
			}

			mediaRecorder = null;
		}
	}

    private boolean doesUserGaveAudioRecordingPermission() {
        return getPermissionState(VoiceRecorder.RECORD_AUDIO_ALIAS).equals(PermissionState.GRANTED);
    }

    private String readRecordedFileAsBase64(File recordedFile) {
        BufferedInputStream bufferedInputStream;
        byte[] bArray = new byte[(int) recordedFile.length()];
        try {
            bufferedInputStream = new BufferedInputStream(new FileInputStream(recordedFile));
            bufferedInputStream.read(bArray);
            bufferedInputStream.close();
        } catch (IOException exp) {
            return null;
        }
        return Base64.encodeToString(bArray, Base64.DEFAULT);
    }

    private int getMsDurationOfAudioFile(String recordedFilePath) {
        try {
            MediaPlayer mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(recordedFilePath);
            mediaPlayer.prepare();
            return mediaPlayer.getDuration();
        } catch (Exception ignore) {
            return -1;
        }
    }

    private boolean isMicrophoneOccupied() {
        AudioManager audioManager = (AudioManager) this.getContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return true;
        return audioManager.getMode() != AudioManager.MODE_NORMAL;
    }
}
