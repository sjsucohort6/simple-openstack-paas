/*
 * Copyright (c) 2015 San Jose State University.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 */

package edu.sjsu.cohort6.openstack.server.job;

import edu.sjsu.cohort6.openstack.client.OpenStack4JClient;
import edu.sjsu.cohort6.openstack.client.OpenStackInterface;
import edu.sjsu.cohort6.openstack.client.ServiceSpec;
import edu.sjsu.cohort6.openstack.db.DBClient;
import edu.sjsu.cohort6.openstack.server.payload.NodePayload;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.image.Image;
import org.openstack4j.model.network.Network;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import java.text.MessageFormat;
import java.util.logging.Logger;

/**
 * Create VM Job.
 *
 * @author rwatsh on 11/12/15.
 */
public class CreateVMJob extends BaseServiceJob implements Job {
    private static final Logger LOGGER = Logger.getLogger(CreateVMJob.class.getName());

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {

        JobDataMap jobDataMap = context.getMergedJobDataMap();
        String serviceName = jobDataMap.getString(JobConstants.SERVICE_NAME);
        String user = jobDataMap.getString(JobConstants.USER);
        String password = jobDataMap.getString(JobConstants.PASSWORD);
        String tenantName = jobDataMap.getString(JobConstants.TENANT_NAME);
        DBClient dbClient = (DBClient) jobDataMap.get(JobConstants.DB_CLIENT);
        JobHelper jobHelper = new JobHelper(dbClient);
        try {

            NodePayload node = (NodePayload) jobDataMap.get(JobConstants.VM_PAYLOAD);
            String flavorName = node.getFlavorName();
            String imageName = node.getImageName();
            String networkName = jobDataMap.getString(JobConstants.NETWORK_NAME);

            //OpenStackInterface client = (OpenStackInterface) jobDataMap.get(JobConstants.OPENSTACK_CLIENT);
            OpenStackInterface client = new OpenStack4JClient(user, password, tenantName);

            Flavor f = client.getFlavorByName(flavorName);
            if (f == null) {
                throw new OpenStackJobException("Could not find flavor " + flavorName);
            }
            Image image = client.getImageByName(imageName);
            if (image == null) {
                throw new OpenStackJobException("Could not find image " + imageName);
            }
            Network net = client.getNetworkByName(networkName);
            if (net == null) {
                LOGGER.info("Could not find network " + networkName + ". Attempting to create the network.");
                net = client.createNetwork(networkName);
                if (net == null) {
                    throw new OpenStackJobException("Could not create network " + networkName);
                }
            }
            String vmName = getVMName(jobDataMap, serviceName);

            String msg = MessageFormat.format("Creating VM {0} with flavor {1} and image {2} and network {3}",
                    vmName, f.getName(), image.getName(), net.getName());
            LOGGER.info(msg);
            jobHelper.saveTaskInfo(context, msg);

            Server server = client.startVM(new ServiceSpec(vmName, f.getId(), image.getId(), net.getId()));

            // check if server was created and started.
            if (server == null) {
                throw new OpenStackJobException("Failed to create server");
            } /*
            Commenting out - server.getMessage() is null here but server does get created.
            else if (!server.getMessage().value().equalsIgnoreCase("running")) {
                throw new OpenStackJobException("Failed to start server " + server.getName() +
                        ". Current status is: " + server.getMessage().value());
            }*/
            int maxRetries = 20;
            int numRetries = 0;
            while (server.getStatus() == null
                    || server.getStatus().value() == null
                    || server.getStatus().value().equalsIgnoreCase("build")) {
                Thread.sleep(60000);
                // Refresh server info.
                Server s = client.getServerByName(vmName);
                server = s == null ? server : s;

                numRetries++;
                if (numRetries >= maxRetries) {
                    break;
                }
            }

            if (numRetries >= maxRetries) {
                throw new OpenStackJobException("Failed to create server in " + maxRetries + " mins: Timed out!");
            } else {
                String msg1 = "Provisioned VM " + server.getName() + " with status " + server.getStatus().value();
                LOGGER.info(msg1);
                jobHelper.saveTaskInfo(context, msg1);
                /*ServiceDAO serviceDAO = (ServiceDAO) dbClient.getDAO(ServiceDAO.class);
                try {
                    Server s = client.getServerByName(vmName);
                    serviceDAO.addNode(serviceName, node.getType(), s);
                } catch (DBException e) {
                    e.printStackTrace();
                    jobHelper.saveTaskInfo(context, msg);
                }*/
                return;
            }
        } catch (Exception e) {
            jobHelper.saveTaskInfo(context, MessageFormat.format("Error occurred in provisioning service {0} (service will be deleted): {1}",
                    serviceName, e.toString()));
            // Provisioning failed, delete the service.
            deleteServiceJob(serviceName, user, password, tenantName, dbClient);

            throw new JobExecutionException(e);
        }
    }
}
