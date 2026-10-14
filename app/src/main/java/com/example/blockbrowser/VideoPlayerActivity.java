package com.example.blockbrowser;
import android.app.Activity;
import android.os.Bundle;
import android.widget.VideoView;
import android.widget.MediaController;
public class VideoPlayerActivity extends Activity {
 @Override public void onCreate(Bundle state){super.onCreate(state);
 VideoView video=new VideoView(this);video.setMediaController(new MediaController(this));setContentView(video);
 video.setVideoURI(getIntent().getData());video.setOnPreparedListener(mp->video.start());
 video.setOnErrorListener((mp,what,extra)->{android.widget.Toast.makeText(this,"This video format is not supported by the device",android.widget.Toast.LENGTH_LONG).show();return false;});
 }
}