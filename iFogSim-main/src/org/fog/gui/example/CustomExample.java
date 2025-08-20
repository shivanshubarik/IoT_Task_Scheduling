package org.fog.gui.example;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;

import org.fog.application.AppEdge;
import org.fog.application.Application;
import org.fog.application.selectivity.FractionalSelectivity;

import org.fog.entities.Actuator;
import org.fog.entities.FogBroker;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.entities.Sensor;

import org.fog.placement.Controller;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacementMapping;

import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;

import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.distribution.DeterministicDistribution;

import java.util.*;

public class CustomExample {

    static List<FogDevice> fogDevices = new ArrayList<>();
    static List<Sensor> sensors = new ArrayList<>();
    static List<Actuator> actuators = new ArrayList<>();

    public static void main(String[] args) {
        Log.printLine("Starting CustomExample...");
        try {
            CloudSim.init(1, Calendar.getInstance(), false);

            FogBroker broker = new FogBroker("broker");

            createFogDevices(broker.getId());

            Application app = createApplication("MyApp", broker.getId());
            app.setUserId(broker.getId());

            // Pin modules to devices (edge vs cloud) explicitly
            ModuleMapping mapping = ModuleMapping.createModuleMapping();
            mapping.addModuleToDevice("ClientModule", "fogNode");  // run at edge
            mapping.addModuleToDevice("ProcessingModule", "cloud"); // offload to cloud

            Controller controller = new Controller("master-controller", fogDevices, sensors, actuators);
            controller.submitApplication(app, new ModulePlacementMapping(fogDevices, app, mapping));

            CloudSim.startSimulation();
            CloudSim.stopSimulation();

            Log.printLine("CustomExample finished!");
        } catch (Exception e) {
            e.printStackTrace();
            Log.printLine("Error in CustomExample: " + e.getMessage());
        }
    }

    private static void createFogDevices(int userId) {
        // cloud (level 0)
        FogDevice cloud = createFogDevice("cloud",
                44800, 40000, 10000, 10000,
                0, 0.01, 16 * 103, 16 * 83.25);
        cloud.setParentId(-1);
        fogDevices.add(cloud);

        // edge node (level 1)
        FogDevice fogNode = createFogDevice("fogNode",
                2800, 4000, 10000, 10000,
                1, 0.0, 107.339, 83.4333);
        fogNode.setParentId(cloud.getId());
        fogNode.setUplinkLatency(20); // ms
        fogDevices.add(fogNode);

        // Attach sensor/actuator to edge node
        // NOTE: Sensor constructor order is (name, tupleType, userId, appId, distribution)
        Sensor sensor = new Sensor("sensor-1", "SENSOR", userId, "MyApp",
                new DeterministicDistribution(5)); // emit every 5 time units
        sensor.setGatewayDeviceId(fogNode.getId());
        sensor.setLatency(1.0);
        sensors.add(sensor);

        Actuator actuator = new Actuator("actuator-1", userId, "MyApp", "ACTUATOR");
        actuator.setGatewayDeviceId(fogNode.getId());
        actuator.setLatency(1.0);
        actuators.add(actuator);
    }

    private static FogDevice createFogDevice(String nodeName,
                                             long mips, int ram,
                                             long upBw, long downBw,
                                             int level, double ratePerMips,
                                             double busyPower, double idlePower) {

        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(0, new PeProvisionerOverbooking(mips))); // 1 PE

        int hostId = FogUtils.generateEntityId();
        long storage = 1_000_000;   // just to satisfy constructor
        int bw = 10_000;

        PowerHost host = new PowerHost(
                hostId,
                new RamProvisionerSimple(ram),
                new BwProvisionerOverbooking(bw),
                storage,
                peList,
                new StreamOperatorScheduler(peList),
                new FogLinearPowerModel(busyPower, idlePower)
        );

        List<Host> hostList = new ArrayList<>();
        hostList.add(host);

        String arch = "x86";
        String os = "Linux";
        String vmm = "Xen";
        double time_zone = 10.0, cost = 3.0, costPerMem = 0.05, costPerStorage = 0.001, costPerBw = 0.0;

        LinkedList<Storage> storageList = new LinkedList<>();

        // iFogSim’s characteristics accept a single host here
        FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
                arch, os, vmm, host, time_zone, cost, costPerMem, costPerStorage, costPerBw
        );

        FogDevice device = null;
        try {
            device = new FogDevice(
                    nodeName,
                    characteristics,
                    new AppModuleAllocationPolicy(hostList),
                    storageList,
                    10,          // scheduling interval
                    upBw,
                    downBw,
                    0,           // internal latency (unused in most setups)
                    ratePerMips
            );
        } catch (Exception e) {
            e.printStackTrace();
        }

        device.setLevel(level);
        return device;
    }

    private static Application createApplication(String appId, int userId) {
        Application application = Application.createApplication(appId, userId);

        // Modules (vertices)
        application.addAppModule("ClientModule", 10);
        application.addAppModule("ProcessingModule", 20);

        // Edges (dataflows)
        // Make sure: import org.fog.application.Tuple;
        application.addAppEdge("SENSOR", "ClientModule",
                1000, 500, "SENSOR", 1, AppEdge.SENSOR);

        application.addAppEdge("ClientModule", "ProcessingModule",
                2000, 1000, "DATA", 1, AppEdge.MODULE);

        application.addAppEdge("ProcessingModule", "ACTUATOR",
                500, 100, "RESULT", 2, AppEdge.ACTUATOR);


        // Tuple mappings (what output each module emits)
        application.addTupleMapping("ClientModule", "SENSOR", "DATA", new FractionalSelectivity(1.0));
        application.addTupleMapping("ProcessingModule", "DATA", "RESULT", new FractionalSelectivity(1.0));

        return application;
    }
}
