package org.scigap.catalog.data;
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.airavata.model.appcatalog.computeresource.*;
import org.apache.airavata.model.data.movement.DataMovementInterface;
import org.apache.airavata.model.data.movement.SCPDataMovement;
import org.apache.airavata.model.parallelism.ApplicationParallelismType;
import org.apache.airavata.registry.api.RegistryService;
import org.apache.airavata.registry.api.client.RegistryServiceClientFactory;
import org.apache.thrift.TException;
import org.sgci.resource.client.SGCIResourceClient;
import org.sgci.resource.client.SGCIResourceException;
import org.sgci.resource.models.ConnectionDefinition;
import org.sgci.resource.models.SGCIResource;
import org.sgci.resource.models.compute.*;
import org.sgci.resource.models.storage.StorageDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hello world!
 *
 */
public class DataFeeder {
    public static void main( String[] args ) throws TException, SGCIResourceException {

        SGCIResourceClient client = new SGCIResourceClient("SGCI/sgci-resource-inventory", "data/compute", "data/storage");
        RegistryService.Client registryClient = RegistryServiceClientFactory.createRegistryClient("", 8970);
        Map<String, String> allComputeResources = registryClient.getAllComputeResourceNames();
        for (String computId : allComputeResources.keySet()) {
            //if (!(computId.contains("test") || computId.contains("Test"))) {
              //  System.out.println(computId);
            //}
            ComputeResourceDescription computeResource = registryClient.getComputeResource(computId);
            SGCIResource sgciResource = new SGCIResource()
                        .setHost(computeResource.getHostName())
                        .setDescription(computeResource.getResourceDescription())
                        .setId(computeResource.getComputeResourceId())
                        .setName(allComputeResources.get(computId))
                        .setResourceType(SGCIResource.ResourceType.COMPUTE);


            ConnectionDefinition connectionDefinition = new ConnectionDefinition();

            if (computeResource.getJobSubmissionInterfaces().size() == 0) {
                continue;
            }
            JobSubmissionInterface jobSubmissionInterface = computeResource.getJobSubmissionInterfaces().get(0);
            JobSubmissionProtocol jobSubProtocol = jobSubmissionInterface.getJobSubmissionProtocol();
            ResourceJobManager resourceJobManager = null;
            switch (jobSubProtocol) {
                case SSH:
                    connectionDefinition.setConnectionProtocol(ConnectionDefinition.ConnectionProtocol.SSH);
                    SSHJobSubmission sshJobSubmission = registryClient.getSSHJobSubmission(jobSubmissionInterface.getJobSubmissionInterfaceId());
                    resourceJobManager = sshJobSubmission.getResourceJobManager();

                    switch (sshJobSubmission.getSecurityProtocol()) {
                        case SSH_KEYS:
                            connectionDefinition.setSecurityProtocol(ConnectionDefinition.SecurityProtocol.SSHKEYS);
                            connectionDefinition.setPort(sshJobSubmission.getSshPort());
                            break;
                        case USERNAME_PASSWORD:
                            connectionDefinition.setSecurityProtocol(ConnectionDefinition.SecurityProtocol.PASSWORDS);
                            connectionDefinition.setPort(sshJobSubmission.getSshPort());
                            break;
                        default:
                            System.out.println("No security protocol was selected for compute resource " + computId);
                    }
                    break;
                default:
                    System.out.println("No connection protocol was selected for compute resource " + computId);
            }

            sgciResource.setConnection(connectionDefinition);


            ComputeDefinition computeDefinition = new ComputeDefinition();

            if (resourceJobManager != null) {

                switch (resourceJobManager.getResourceJobManagerType()) {
                    case SLURM:
                    case PBS:
                        BatchSystemDefinition batchSystemDefinition = new BatchSystemDefinition();

                        List<PartitionDefinition> partitions = new ArrayList<>();
                        List<BatchQueue> batchQueues = computeResource.getBatchQueues();

                        for (BatchQueue bq : batchQueues) {
                            PartitionDefinition pd = new PartitionDefinition();
                            pd.setName(bq.getQueueName());
                            pd.setTotalNodes(bq.getMaxNodes());
                            pd.setNodeHardware(new NodeHardwareDefinition()
                                    .setCpuCount(bq.getCpuPerNode())
                                    .setMemorySize(bq.getMaxMemory() + ""))
                                    .setComputeQuotas(new ComputeQuota()
                                            .setMaxCPUsPerJob(bq.getMaxProcessors())
                                            .setMaxMemoryPerJob(bq.getMaxMemory())
                                            .setMaxNodesPerJob(bq.getMaxNodes())
                                            .setMaxJobsTotal(bq.getMaxJobsInQueue())
                                            .setMaxTimePerJob(bq.getMaxRunTime()));
                            partitions.add(pd);
                        }

                        batchSystemDefinition.setPartitions(partitions);

                        List<CommandPathDefinition> commandPaths = new ArrayList<>();


                        resourceJobManager.getJobManagerCommands().forEach((name, path) -> {
                            commandPaths.add(new CommandPathDefinition().setName(name.name()).setPath(path));
                        });

                        batchSystemDefinition.setCommandPaths(commandPaths);
                        computeDefinition.setBatchSystem(batchSystemDefinition);
                        computeDefinition.setSchedulerType(ComputeDefinition.SchedulerType.BATCH);

                        Map<ApplicationParallelismType, String> parallelismPrefixes = resourceJobManager.getParallelismPrefix();
                        List<ExecutionCommandDefinition> executionCommands = new ArrayList<>();
                        parallelismPrefixes.forEach((prefix, value) -> {
                            ExecutionCommandDefinition ecd = new ExecutionCommandDefinition();
                            ecd.setCommandType(prefix.name());
                            ecd.setCommandPrefix(value);
                            executionCommands.add(ecd);
                        });

                        computeDefinition.setExecutionCommands(executionCommands);
                        break;
                    case FORK:
                        // TODO implement
                        break;
                }
            }

            StorageDefinition storageDefinition = new StorageDefinition();

            if (computeResource.getDataMovementInterfaces().size() > 0 ) {
                DataMovementInterface dataMovementInterface = computeResource.getDataMovementInterfaces().get(0);
                switch (dataMovementInterface.getDataMovementProtocol()) {
                    case SCP:
                        storageDefinition.setStorageType(StorageDefinition.StorageType.POSIX);
                        ConnectionDefinition storageConnection = new ConnectionDefinition();
                        storageConnection.setConnectionProtocol(ConnectionDefinition.ConnectionProtocol.SCP);
                        SCPDataMovement scpDataMovement = registryClient.getSCPDataMovement(dataMovementInterface.getDataMovementInterfaceId());
                        switch (scpDataMovement.getSecurityProtocol()) {
                            case SSH_KEYS:
                                storageConnection.setSecurityProtocol(ConnectionDefinition.SecurityProtocol.SSHKEYS);
                                break;
                            case USERNAME_PASSWORD:
                                storageConnection.setSecurityProtocol(ConnectionDefinition.SecurityProtocol.PASSWORDS);
                                break;
                        }
                        storageConnection.setPort(scpDataMovement.getSshPort() == 0 ? 22 : scpDataMovement.getSshPort());
                        storageDefinition.setConnection(storageConnection);
                        break;
                }

                computeDefinition.setStorageResource(storageDefinition);
            }
            sgciResource.setResource(computeDefinition);

            client.addResourceToGit(computeResource.getComputeResourceId(), sgciResource);
        }
    }
}
