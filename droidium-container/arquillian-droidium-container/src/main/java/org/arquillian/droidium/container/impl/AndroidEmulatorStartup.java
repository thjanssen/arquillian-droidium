/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.arquillian.droidium.container.impl;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.arquillian.droidium.container.api.AndroidBridge;
import org.arquillian.droidium.container.api.AndroidDevice;
import org.arquillian.droidium.container.api.AndroidExecutionException;
import org.arquillian.droidium.container.api.Screenshooter;
import org.arquillian.droidium.container.configuration.AndroidContainerConfiguration;
import org.arquillian.droidium.container.configuration.AndroidSDK;
import org.arquillian.droidium.container.configuration.Command;
import org.arquillian.droidium.container.spi.event.AndroidDeviceReady;
import org.arquillian.droidium.container.spi.event.AndroidVirtualDeviceAvailable;
import org.jboss.arquillian.container.spi.context.annotation.ContainerScoped;
import org.jboss.arquillian.core.api.Event;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.InstanceProducer;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.core.api.annotation.Observes;

import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.AndroidDebugBridge.IDeviceChangeListener;
import com.android.ddmlib.IDevice;

/**
 * Starts an emulator and either connects to an existing device or creates one. <br>
 * <br>
 * Observes:
 * <ul>
 * <li>{@link AndroidVirtualDeviceAvailable}</li>
 * </ul>
 *
 * Creates:
 * <ul>
 * <li>{@link AndroidEmulator}</li>
 * <li>{@link AndroidDevice}</li>
 * <li>{@link Screenshooter}</li>
 * </ul>
 *
 * Fires:
 * <ul>
 * <li>{@link AndroidDeviceReady}</li>
 * </ul>
 *
 * @author <a href="kpiwko@redhat.com">Karel Piwko</a>
 * @author <a href="smikloso@redhat.com">Stefan Miklosovic</a>
 *
 */
public class AndroidEmulatorStartup {

    private static final Logger logger = Logger.getLogger(AndroidEmulatorStartup.class.getName());

    @Inject
    @ContainerScoped
    private InstanceProducer<AndroidEmulator> androidEmulator;

    @Inject
    @ContainerScoped
    private InstanceProducer<AndroidDevice> androidDevice;

    @Inject
    @ContainerScoped
    private InstanceProducer<Screenshooter> screenshooter;

    @Inject
    private Instance<AndroidBridge> androidBridge;

    @Inject
    private Instance<ProcessExecutor> executor;

    @Inject
    private Instance<AndroidContainerConfiguration> configuration;

    @Inject
    private Instance<AndroidSDK> androidSDK;

    @Inject
    private Event<AndroidDeviceReady> androidDeviceReady;

    public void startAndroidEmulator(@Observes AndroidVirtualDeviceAvailable event) throws AndroidExecutionException {
        if (!androidBridge.get().isConnected()) {
            throw new IllegalStateException("Android debug bridge must be connected in order to spawn the emulator");
        }

        logger.log(Level.INFO, "Starting Android emulator of AVD name {0}.", configuration.get().getAvdName());

        AndroidContainerConfiguration configuration = this.configuration.get();
        AndroidDevice emulator = null;

        CountDownWatch countdown = new CountDownWatch(configuration.getEmulatorBootupTimeoutInSeconds(), TimeUnit.SECONDS);
        logger.log(Level.INFO, "Waiting {0} seconds for emulator {1} to be started and connected.",
                new Object[] { countdown.timeout(), configuration.getAvdName() });

        ProcessExecutor emulatorProcessExecutor = this.executor.get();
        DeviceConnectDiscovery deviceDiscovery = new DeviceConnectDiscovery();
        AndroidDebugBridge.addDeviceChangeListener(deviceDiscovery);

        ProcessExecution emulatorExecution = startEmulator(emulatorProcessExecutor);

        androidEmulator.set(new AndroidEmulator(emulatorExecution));

        logger.log(Level.INFO, "Emulator process started, {0} seconds remaining to start the device {1}", new Object[] {
                countdown.timeLeft(), configuration.getAvdName() });

        waitUntilBootUpIsComplete(deviceDiscovery, emulatorExecution, emulatorProcessExecutor, countdown);

        unlockEmulator(deviceDiscovery, emulatorProcessExecutor);

        emulator = deviceDiscovery.getDiscoveredDevice();
        setDronePorts(emulator);

        AndroidDebugBridge.removeDeviceChangeListener(deviceDiscovery);

        androidDevice.set(emulator);
        screenshooter.set(new AndroidScreenshooter(emulator));

        androidDeviceReady.fire(new AndroidDeviceReady(emulator));
    }

    private void setDronePorts(AndroidDevice device) {
        device.setDroneHostPort(configuration.get().getDroneHostPort());
        device.setDroneGuestPort(configuration.get().getDroneGuestPort());
    }

    private ProcessExecution startEmulator(ProcessExecutor executor) throws AndroidExecutionException {

        AndroidSDK sdk = this.androidSDK.get();
        AndroidContainerConfiguration configuration = this.configuration.get();

        logger.log(Level.INFO, configuration.toString());

        Command command = new Command();
        command.add(sdk.getEmulatorPath()).add("-avd").add(configuration.getAvdName());

        if (configuration.getSdCard() != null) {
            command.add("-sdcard");
            command.add(configuration.getSdCard());
        }

        if (configuration.getConsolePort() != null && configuration.getAdbPort() != null) {
            command.add("-ports").addAsString(configuration.getConsolePort() + "," + configuration.getAdbPort());
        } else if (configuration.getConsolePort() != null) {
            command.add("-port").add(configuration.getConsolePort());
        }

        command.addAsString(configuration.getEmulatorOptions());

        logger.log(Level.INFO, "Starting emulator \"{0}\", using {1}", new Object[] { configuration.getAvdName(), command });

        // define what patterns would be consider worthy to notify user
        ProcessInteractionBuilder interactions = new ProcessInteractionBuilder()
                .errors("^SDL init failure.*$")
                .errors("^PANIC:.*$")
                .errors("^error.*$")
                .errors("^.*$");

        // execute emulator
        try {
            return executor.spawn(interactions.build(), command);
        } catch (AndroidExecutionException e) {
            throw new AndroidExecutionException(e, "Unable to start emulator \"{0}\", using {1}", configuration.getAvdName(),
                    command);
        }
    }

    private void unlockEmulator(final DeviceConnectDiscovery deviceDiscovery, final ProcessExecutor executor) {

        final AndroidDevice connectedDevice = deviceDiscovery.getDiscoveredDevice();
        final String serialNumber = connectedDevice.getSerialNumber();
        Command unlockPart1 = new Command(androidSDK.get().getAdbPath(), "-s", serialNumber, "shell", "input", "keyevent", "82");
        Command unlockPart2 = new Command(androidSDK.get().getAdbPath(), "-s", serialNumber, "shell", "input", "keyevent", "4");

        try {
            executor.execute(ProcessInteractionBuilder.NO_INTERACTION, unlockPart1);
            executor.execute(ProcessInteractionBuilder.NO_INTERACTION, unlockPart2);
        } catch (final AndroidExecutionException e) {
            logger.log(Level.WARNING, "Unlocking device failed", e);
            // exception is ignored, logged only
        }
    }

    private void waitUntilBootUpIsComplete(final DeviceConnectDiscovery deviceDiscovery,
            final ProcessExecution emulatorExecution, final ProcessExecutor executor, final CountDownWatch countdown)
            throws AndroidExecutionException {

        try {
            boolean isOnline = executor.scheduleUntilTrue(new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    // ARQ-1583 check status of emulator
                    if (emulatorExecution.isFinished()) {
                        throw new IllegalStateException("Emulator device startup exited prematurely with exit code "
                                + emulatorExecution.getExitCode());
                    }

                    return deviceDiscovery.isOnline();
                }
            }, countdown.timeLeft(), countdown.getTimeUnit().convert(1, TimeUnit.SECONDS), countdown.getTimeUnit());

            if (isOnline == false) {
                throw new IllegalStateException(
                        "No emulator device was brough online during "
                                + countdown.timeout()
                                + " seconds to Android Debug Bridge. Please increase the time limit in order to get emulator connected.");
            }

            // device is connected to ADB
            final AndroidDevice connectedDevice = deviceDiscovery.getDiscoveredDevice();

            logger.log(Level.INFO, "Serial number: " + connectedDevice.getSerialNumber());

            final String adbPath = androidSDK.get().getAdbPath();
            logger.log(Level.INFO, "adbPath: " + adbPath);
            final String serialNumber = connectedDevice.getSerialNumber();
            logger.log(Level.INFO, "serial number: " + serialNumber);

            isOnline = executor.scheduleUntilTrue(new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    // ARQ-1583 check status of emulator
                    if (emulatorExecution.isFinished()) {
                        throw new IllegalStateException("Emulator device startup exited prematurely with exit code "
                                + emulatorExecution.getExitCode());
                    }

                    // check properties of underlying process
                    Command propertiesCheck = new Command(adbPath, "-s", serialNumber, "shell", "getprop");
                    ProcessExecution properties = executor.execute(ProcessInteractionBuilder.NO_INTERACTION, propertiesCheck);
                    for (String line : properties.getOutput()) {
                        if (line.contains("[ro.runtime.firstboot]")) {
                            // boot is completed
                            return true;
                        }
                    }
                    return false;
                }
            }, countdown.timeLeft(), countdown.getTimeUnit().convert(1, TimeUnit.SECONDS), countdown.getTimeUnit());

            if (logger.isLoggable(Level.INFO)) {
                logger.log(Level.INFO, "Android emulator {0} was started within {1} seconds",
                        new Object[] { connectedDevice.getAvdName(), countdown.timeElapsed() });
            }

            if (isOnline == false) {
                throw new AndroidExecutionException("Emulator device hasn't started properly in " + countdown.timeout()
                        + " seconds. Please increase the time limit in order to get emulator booted.");
            }
        } catch (InterruptedException e) {
            throw new AndroidExecutionException(e, "Emulator device startup failed.");
        } catch (ExecutionException e) {
            logger.log(Level.INFO, e.getCause().toString());
            throw new AndroidExecutionException(e, "Emulator device startup failed.");
        }
    }

    private class DeviceConnectDiscovery implements IDeviceChangeListener {

        private IDevice discoveredDevice;

        private boolean online;

        @Override
        public void deviceChanged(IDevice device, int changeMask) {
            if (discoveredDevice.equals(device) && (changeMask & IDevice.CHANGE_STATE) == 1) {
                if (device.isOnline()) {
                    this.online = true;
                }
            }
        }

        @Override
        public void deviceConnected(IDevice device) {
            this.discoveredDevice = device;
            logger.log(Level.INFO, "Discovered an emulator device id={0} connected to ADB bus", device.getSerialNumber());
        }

        @Override
        public void deviceDisconnected(IDevice device) {
        }

        public AndroidDevice getDiscoveredDevice() {
            return new AndroidDeviceImpl(discoveredDevice);
        }

        public boolean isOnline() {
            return online;
        }
    }
}
