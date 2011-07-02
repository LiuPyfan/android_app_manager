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

package ros.android.util;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.util.Log;
import org.ros.NodeConfiguration;
import org.ros.RosLoader;
import org.ros.exception.RosInitException;
import org.ros.internal.namespace.GraphName;
import org.ros.namespace.NameResolver;
import org.yaml.snakeyaml.Yaml;
import ros.android.activity.MasterChooserActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Enumeration;
import java.util.HashMap;

/**
 * Helper class for launching the {@link MasterChooserActivity} for choosing a
 * ROS master. Keep this object around for the lifetime of an {@link Activity}.
 */
public class MasterChooser extends RosLoader {

  private Activity callingActivity;
  private RobotDescription currentRobot;

  /**
   * REQUEST_CODE number must be unique among activity requests which might be
   * seen by handleActivityResult().
   */
  private static final int REQUEST_CODE = 8748792;

  /**
   * Constructor. Does not read current master from disk, that must be done by
   * calling loadCurrentRobot().
   */
  public MasterChooser(Activity callingActivity) {
    this.callingActivity = callingActivity;
    currentRobot = null;
  }

  public RobotDescription getCurrentRobot() {
    return currentRobot;
  }

  public void setCurrentRobot( RobotDescription robot ) {
    currentRobot = robot;
  }

  /**
   * Returns a File for the current-robot file if the sdcard is ready and
   * there's no error, null otherwise. The actual file on "disk" does not have
   * to exist for this to work and return a File object.
   */
  private File getCurrentRobotFile() {
    if (!SdCardSetup.isReady()) {
      return null;
    } else {
      try {
        File rosDir = SdCardSetup.getRosDir();
        return new File(rosDir, "current_robot.yaml");
      } catch (Exception ex) {
        Log.e("MasterChooser", "exception in getCurrentRobotFile: " + ex.getMessage());
        return null;
      }
    }
  }

  /**
   * Write the current value of private currentRobot variable to a common file
   * on the sdcard, so it can be shared between ROS apps.
   */
  public void saveCurrentRobot() {
    File currentRobotFile = getCurrentRobotFile();
    if (currentRobotFile == null) {
      Log.e("MasterChooser", "saveCurrentRobot(): could not get current-robot File object.");
      return;
    }

    try {
      if (!currentRobotFile.exists()) {
        Log.i("MasterChooser", "current-robot file does not exist, creating.");
        currentRobotFile.createNewFile();
      }

      // overwrite the file contents.
      FileWriter writer = new FileWriter(currentRobotFile, false);
      Yaml yaml = new Yaml();
      yaml.dump(currentRobot, writer);
      writer.close();
      Log.i("MasterChooser", "Wrote '" + currentRobot.getRobotId().toString() + "' to current-robot file.");
    } catch (Exception ex) {
      Log.e("MasterChooser", "exception writing current robot to sdcard: " + ex.getMessage());
    }
  }

  /**
   * Read the current robot description from a file shared by ROS applications,
   * so we don't have to re-choose the robot for each new app launch. If the
   * file does not exist or has invalid data, haveMaster() will return false
   * after this. On success, private currentRobot variable is set. On failure,
   * nothing is changed.
   */
  public void loadCurrentRobot() {
    try {
      File currentRobotFile = getCurrentRobotFile();
      if (currentRobotFile == null) {
        Log.e("MasterChooser", "loadCurrentRobot(): can't get the current-robot file.");
        return;
      }

      BufferedReader reader = new BufferedReader(new FileReader(currentRobotFile));
      try {
        Yaml yaml = new Yaml();
        currentRobot = (RobotDescription) yaml.load(reader);
      } finally {
        reader.close();
      }
    } catch (Throwable ex) {
      Log.e("MasterChooser", "exception reading current-robot file: " + ex.getMessage());
    }
  }

  /**
   * Returns true if current master URI and robot name are set in memory, false
   * otherwise. Does not read anything from disk.
   */
  public boolean hasRobot() {
    return (currentRobot != null && currentRobot.getRobotId() != null
            && currentRobot.getRobotId().getMasterUri() != null
            && currentRobot.getRobotId().getMasterUri().toString().length() != 0
            && currentRobot.getRobotName() != null && currentRobot.getRobotName().length() != 0);
  }

  /**
   * Call this from your activity's onActivityResult() to record the master URI.
   * This does not write to the current-master file on the sdcard, that must be
   * done explicitly. The return value of this function only indicates whether
   * the request-result described by the parameters is appropriate for this
   * class. It does not indicate anything about the validity of the returned
   * master URI.
   * 
   * @returns true if the activity result came from the activity started by this
   *          class, false otherwise.
   */
  public boolean handleActivityResult(int requestCode, int resultCode, Intent resultIntent) {
    if (requestCode != REQUEST_CODE)
      return false;

    if (resultCode == Activity.RESULT_OK) {
      currentRobot = (RobotDescription) resultIntent
          .getSerializableExtra(MasterChooserActivity.ROBOT_DESCRIPTION_EXTRA);
    }
    return true;
  }

  /**
   * Launch the {@link MasterChooserActivity} to choose or scan a new master.
   * Because this launches an activity, the caller's {@code onPause()},
   * {@code onActivityResult()} and {@code onResume()} functions will be called
   * before anything else happens there.
   */
  public void launchChooserActivity() throws ActivityNotFoundException {
    Log.i("MasterChooser", "starting master chooser activity");
    Intent chooserIntent = new Intent(callingActivity, MasterChooserActivity.class);
    callingActivity.startActivityForResult(chooserIntent, REQUEST_CODE);
  }

  /**
   * Create and return a new ROS NodeContext object based on the current value
   * of the internal masterUri variable.
   * 
   * @throws RosInitException
   *           If the master URI is invalid or if we cannot get a hostname for
   *           the device we are running on.
   */
  @Override
  public NodeConfiguration createConfiguration() throws RosInitException {
    return createConfiguration(currentRobot.getRobotId());
  }

  /**
   * Create and return a new ROS NodeContext object.
   * 
   * @throws RosInitException
   *           If masterUri is invalid or if we cannot get a hostname for the
   *           device we are running on.
   */
  static public NodeConfiguration createConfiguration(RobotId robotId) throws RosInitException {
    Log.i("MasterChooser", "createConfiguration(" + robotId.toString() + ")");
    if (robotId == null || robotId.getMasterUri() == null) {
      // TODO: different exception type for invalid master uri
      throw new RosInitException("ROS Master URI is not set");
    }
    String namespace = "/";
    HashMap<GraphName, GraphName> remappings = new HashMap<GraphName, GraphName>();
    NameResolver resolver = new NameResolver(new GraphName(namespace), remappings);

    URI uri;
    try {
      uri = new URI(robotId.getMasterUri());
    } catch (URISyntaxException e) {
      Log.i("MasterChooser", "createConfiguration(" + robotId.toString() + ") invalid master uri.");
      throw new RosInitException("Invalid master URI");
    }

    Log.i("MasterChooser", "createConfiguration() creating configuration.");
    NodeConfiguration configuration = NodeConfiguration.createDefault();
    configuration.setParentResolver(resolver);
    configuration.setRosPackagePath(null);
    configuration.setMasterUri(uri);

    configuration.setHost(getNonLoopbackHostName());
    Log.i("MasterChooser", "createConfiguration() returning configuration with host = " + configuration.getHost());
    return configuration;
  }

  /**
   * @return The first valid non-loopback, IPv4 host name (address in text form
   *         like "10.0.129.222" found for this device.
   */
  private static String getNonLoopbackHostName() {
    Log.i("MasterChooser", "getNonLoopbackHostName() starts");
    try {
      String address = null;
      for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en
          .hasMoreElements();) {
        NetworkInterface intf = en.nextElement();
        Log.i("MasterChooser", "getNonLoopbackHostName() sees interface: " + intf.getName());
        for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr
            .hasMoreElements();) {
          InetAddress inetAddress = enumIpAddr.nextElement();
          Log.i("MasterChooser", "getNonLoopbackHostName() sees address: " + inetAddress.getHostAddress().toString());
          // IPv4 only for now
          if (!inetAddress.isLoopbackAddress() && inetAddress.getAddress().length == 4) {
            if (address == null)
              address = inetAddress.getHostAddress().toString();
          }
        }
      }
      if (address != null) {
        Log.i("MasterChooser", "getNonLoopbackHostName() returning " + address);
        return address;
      }
    } catch (SocketException ex) {
      Log.i("MasterChooser", "getNonLoopbackHostName() caught SocketException: " + ex.getMessage());
    }
    Log.i("MasterChooser", "getNonLoopbackHostName() returning null.");
    return null;
  }
}
