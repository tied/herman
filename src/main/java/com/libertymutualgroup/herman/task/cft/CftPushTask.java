/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.libertymutualgroup.herman.task.cft;

import com.amazonaws.regions.Regions;
import com.atlassian.bamboo.build.logger.BuildLogger;
import com.atlassian.bamboo.deployments.execution.DeploymentTaskContext;
import com.atlassian.bamboo.task.TaskException;
import com.atlassian.bamboo.task.TaskResult;
import com.atlassian.bamboo.task.TaskResultBuilder;
import com.atlassian.bamboo.variable.CustomVariableContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.libertymutualgroup.herman.aws.AbstractDeploymentTask;
import com.libertymutualgroup.herman.aws.CredentialsHandler;
import com.libertymutualgroup.herman.aws.cft.CftPush;
import java.io.InputStream;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;

public class CftPushTask extends AbstractDeploymentTask {

    private final static String TASK_CONFIG_FILE = "/config/plugin-tasks.yml";

    @Autowired
    public CftPushTask(CustomVariableContext customVariableContext) {
        super(customVariableContext);
    }

    @Override
    public TaskResult doExecute(final DeploymentTaskContext taskContext) throws TaskException {
        final BuildLogger buildLogger = taskContext.getBuildLogger();

        CftPush push = new CftPush(buildLogger, taskContext, CredentialsHandler.getCredentials(taskContext),
            CredentialsHandler.getConfiguration(), Regions.fromName(taskContext.getConfigurationMap().get("awsRegion")),
            getCustomVariableContext(), getTaskProperties());
        push.push();

        return TaskResultBuilder.newBuilder(taskContext).success().build();
    }

    CFTPushTaskProperties getTaskProperties() {
        try {
            InputStream lambdaCreateTaskPropertiesStream = getClass().getResourceAsStream(TASK_CONFIG_FILE);
            String lambdaCreateTaskPropertiesYml = IOUtils.toString(lambdaCreateTaskPropertiesStream);
            ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
            return objectMapper.readValue(lambdaCreateTaskPropertiesYml, CFTPushTaskProperties.class);
        } catch (Exception ex) {
            throw new RuntimeException("Error getting Cft Push Task Properties from " + TASK_CONFIG_FILE, ex);
        }
    }

}