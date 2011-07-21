/*
 * Software License Agreement (BSD License)
 *
 * Copyright (c) 2011, Willow Garage, Inc.
 * All rights reserved.
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above
 *    copyright notice, this list of conditions and the following
 *    disclaimer in the documentation and/or other materials provided
 *    with the distribution.
 *  * Neither the name of Willow Garage, Inc. nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *    
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package ros.android.activity;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;
import org.yaml.snakeyaml.Yaml;
import ros.android.util.InvalidRobotDescriptionException;
import ros.android.util.MasterChooser;
import ros.android.util.RobotDescription;
import ros.android.util.RobotId;
import ros.android.util.SdCardSetup;
import ros.android.util.zxing.IntentIntegrator;
import ros.android.util.zxing.IntentResult;

import java.util.Map;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * @author hersh@willowgarage.com
 */
public class MasterChooserActivity extends Activity {

  private static final int ADD_URI_DIALOG_ID = 0;

  public static final String ROBOT_DESCRIPTION_EXTRA = "org.ros.android.RobotDescription";

  // don't modify this without immediately calling updateListView().
  private List<RobotDescription> robots;
  private MasterChooser currentRobotAccessor;

  public MasterChooserActivity() {
    robots = new ArrayList<RobotDescription>();
    currentRobotAccessor = new MasterChooser(this);
  }

  private File getRobotListFile() {
    if (!SdCardSetup.isReady()) {
      SdCardSetup.promptUserForMount(this);
      return null;
    } else {
      try {
        File rosDir = SdCardSetup.getRosDir();
        File robotListFile = new File(rosDir, "robots.yaml");
        if (!robotListFile.exists()) {
          Log.i("RosAndroid", "robots.yaml file does not exist, creating.");
          robotListFile.createNewFile();
        }
        return robotListFile;
      } catch (Exception ex) {
        Log.e("RosAndroid", "exception in getRobotListFile: " + ex.getMessage());
        return null;
      }
    }
  }

  public void writeRobotList() {
    File robotListFile = getRobotListFile();
    if (robotListFile == null) {
      Log.e("RosAndroid", "writeNewRobot(): no robots file.");
      return;
    }

    try {
      FileWriter writer = new FileWriter(robotListFile);
      Yaml yaml = new Yaml();
      yaml.dump(robots, writer);
      writer.close();
      Log.i("RosAndroid", "Wrote robots.yaml file.");
    } catch (Exception ex) {
      Log.e("RosAndroid", "exception writing robots.yaml to sdcard: " + ex.getMessage());
    }
  }

  @SuppressWarnings("unchecked")
  private void readRobotList() {
    try {
      File robotListFile = getRobotListFile();
      if (robotListFile == null) {
        Log.e("RosAndroid", "readRobotList(): no robots.yaml file.");
        return;
      }

      BufferedReader reader = new BufferedReader(new FileReader(robotListFile));
      try {
        Yaml yaml = new Yaml();
        robots = (List<RobotDescription>) yaml.load(reader);
        if (robots == null) {
          robots = new ArrayList<RobotDescription>();
        }
      } finally {
        reader.close();
      }
    } catch (Exception ex) {
      Log.e("RosAndroid", "exception reading list of previous master URIs: " + ex.getMessage());
    }
  }

  /** Called when the activity is first created. */
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setTitle("Choose a ROS Master");
    readRobotList();
  }

  @Override
  protected void onResume() {
    super.onResume();
    refresh();
  }

  private void refresh() {
    currentRobotAccessor.loadCurrentRobot();
    readRobotList();
    updateListView();
  }

  private void updateListView() {
    setContentView(R.layout.advanced_master_chooser);
    ListView listview = (ListView) findViewById(R.id.master_list);
    listview.setAdapter(new MasterAdapter(this, robots));
    listview.setOnItemClickListener(new OnItemClickListener() {
      @Override
      public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
        choose(position);
      }
    });
    int index = 0;
    for( RobotDescription robot: robots ) {
      if( robot != null && robot.equals( currentRobotAccessor.getCurrentRobot() )) {
        Log.i("MasterChooserActivity", "Highlighting index " + index);
        listview.setItemChecked(index, true);
        break;
      }
      index++;
    }
  }

  private void choose(int position) {
    Intent resultIntent = new Intent();
    resultIntent.putExtra(ROBOT_DESCRIPTION_EXTRA, robots.get(position));
    setResult(RESULT_OK, resultIntent);
    finish();
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent intent) {
    IntentResult scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, intent);
    if (scanResult != null) {
      Yaml yaml = new Yaml();
      Map<String, Object> data = (Map<String, Object>)yaml.load(scanResult.getContents().toString());
      Log.i("MasterChooserActivity", "OBJECT: " + data.toString());
      try {
        addMaster(new RobotId(data));
      } catch (InvalidRobotDescriptionException e) {
        Toast.makeText(this, "Invalid robot description: "+e.getMessage(), Toast.LENGTH_SHORT).show();
      }
    } else {
      Toast.makeText(this, "Scan failed", Toast.LENGTH_SHORT).show();
    }
  }

  private void addMaster(RobotId robotId) throws InvalidRobotDescriptionException {
    Log.i("MasterChooserActivity", "addMaster ["+robotId.toString()+"]");
    if (robotId == null || robotId.getMasterUri() == null) {
      throw new InvalidRobotDescriptionException("Empty master URI");
    } else {
      //TODO: in YAML loading!!!
      /*if (!masterUri.startsWith("http://") && !masterUri.startsWith("https://")) {
        masterUri = "http://" + masterUri;
      }
      URI uri;
      try {
        uri = new URI(masterUri);
      } catch (URISyntaxException e) {
        throw new InvalidRobotDescriptionException("Invalid master URI");
      }
      if (uri.getPort() < 0) {
        try {
          uri = new URI(uri.getScheme() + "://" + uri.getHost() + ":11311");
        } catch (URISyntaxException e) {
          throw new InvalidRobotDescriptionException("internal error");
        }
        }*/

      Iterator<RobotDescription> iter = robots.iterator();
      while (iter.hasNext()) {
        RobotDescription robot = iter.next();
        if (robot.getRobotId().equals(robotId)) {
          Toast.makeText(this, "That robot is already listed.", Toast.LENGTH_SHORT).show();
          return;
        }
      }
      Log.i("MasterChooserActivity", "creating robot description: "+robotId.toString());
      robots.add(RobotDescription.createUnknown(robotId));
      Log.i("MasterChooserActivity", "description created");
      onRobotsChanged();
    }
  }

  private void onRobotsChanged() {
    writeRobotList();
    updateListView();
  }

  private void deleteUnresponsiveRobots() {
    Iterator<RobotDescription> iter = robots.iterator();
    while (iter.hasNext()) {
      RobotDescription robot = iter.next();
      if (robot == null || robot.getConnectionStatus() == null
          || robot.getConnectionStatus().equals(robot.ERROR)) {
        Log.i("RosAndroid", "Removing robot with connection status '" + robot.getConnectionStatus()
            + "'");
        iter.remove();
        if( robot != null && robot.equals( currentRobotAccessor.getCurrentRobot() )) {
          currentRobotAccessor.setCurrentRobot( null );
          currentRobotAccessor.saveCurrentRobot();
        }
      }
    }
    onRobotsChanged();
  }

  private void deleteAllRobots() {
    robots.clear();
    onRobotsChanged();
    currentRobotAccessor.setCurrentRobot( null );
    currentRobotAccessor.saveCurrentRobot();
  }

  @Override
  protected Dialog onCreateDialog(int id) {
    Dialog dialog;
    Button button;
    switch (id) {
    case ADD_URI_DIALOG_ID:
      dialog = new Dialog(this);
      dialog.setContentView(R.layout.add_uri_dialog);
      dialog.setTitle("Add a robot");
      EditText uriField = (EditText) dialog.findViewById(R.id.uri_editor);
      uriField.setOnKeyListener(new URIFieldKeyListener());
      button = (Button) dialog.findViewById(R.id.scan_robot_button);
      button.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          scanRobotClicked(v);
        }
      });
      button = (Button) dialog.findViewById(R.id.cancel_button);
      button.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          dismissDialog(ADD_URI_DIALOG_ID);
        }
      });
      break;
    default:
      dialog = null;
    }
    return dialog;
  }

  public void addRobotClicked(View view) {
    showDialog(ADD_URI_DIALOG_ID);
  }

  public void refreshClicked(View view) {
    refresh();
  }

  public void scanRobotClicked(View view) {
    dismissDialog(ADD_URI_DIALOG_ID);
    IntentIntegrator.initiateScan(this, IntentIntegrator.DEFAULT_TITLE,
        IntentIntegrator.DEFAULT_MESSAGE, IntentIntegrator.DEFAULT_YES,
        IntentIntegrator.DEFAULT_NO, IntentIntegrator.QR_CODE_TYPES);
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    MenuInflater inflater = getMenuInflater();
    inflater.inflate(R.menu.master_chooser_options_menu, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
    case R.id.add_robot:
      showDialog(ADD_URI_DIALOG_ID);
      return true;
    case R.id.delete_unresponsive:
      deleteUnresponsiveRobots();
      return true;
    case R.id.delete_all:
      deleteAllRobots();
      return true;
    case R.id.kill:
      android.os.Process.killProcess(android.os.Process.myPid());
      return true;
    default:
      return super.onOptionsItemSelected(item);
    }
  }

  public class URIFieldKeyListener implements View.OnKeyListener {
    @Override
    public boolean onKey(View view, int keyCode, KeyEvent event) {
      if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
        EditText uriField = (EditText) view;
        String newMasterUri = uriField.getText().toString();
        if (newMasterUri != null && newMasterUri.length() > 0) {
          try {
            addMaster(new RobotId(newMasterUri));
          } catch (InvalidRobotDescriptionException e) {
            Toast.makeText(MasterChooserActivity.this, "Invalid URI", Toast.LENGTH_SHORT).show();
          }
        }
        dismissDialog(ADD_URI_DIALOG_ID);
        return true;
      }
      return false;
    }
  }
}
