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
package com.libertymutualgroup.herman.cli;

import com.libertymutualgroup.herman.logging.SysoutLogger;
import com.libertymutualgroup.herman.task.cli.ecs.ECSPushTask;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(description = "Runs Herman the AWS Task Helper", name = "herman", mixinStandardHelpOptions = true)
public class Cli implements Callable<Void> {

    @CommandLine.Parameters(paramLabel = "TASK", description = "Task to be executed", arity = "1")
    private Tasks task;

    @Option(names = {"-d", "--directory"}, description = "Path to configuration files", showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
    private String rootPath = System.getProperty("user.dir");

    @Option(names = {"-e", "-env", "--environment"}, description = "Environment to deploy")
    private String environmentName;

    @Option(names = {"-t", "--timeout"}, description = "Task timeout (in minutes)", showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
    private int timeout = 5;

    @Option(names = {"-v", "-vars", "--variables"}, description = "Custom build variables to be injected. <KEY>==<VALUE>")
    private Map<String, String> customVariables = new HashMap<>();

    public static void main(String[] args) {
        CommandLine.call(new Cli(), args);
    }

    @Override public Void call() throws Exception {
        SysoutLogger logger = new SysoutLogger();
        if (task.equals(Tasks.CFT_PUSH)) {
            logger.addErrorLogEntry("CFT Push not yet implemented in CLI");
        }
        String absPath = new File(this.rootPath).getAbsolutePath();

        switch (task) {
            case ECS_PUSH:
                logger.addLogEntry("Starting ECS Push");
                ECSPushTask ecsPush = new ECSPushTask(logger);
                ecsPush.doExecute(absPath, timeout, environmentName, customVariables);
                break;
            case CFT_PUSH:
                logger.addErrorLogEntry("Not yet implemented in CLI");
                System.exit(1);
                break;
            case ECR_REPO_CREATE:
                logger.addErrorLogEntry("Not yet implemented in CLI");
                System.exit(1);
                break;
            case LAMBDA_CREATE:
                logger.addErrorLogEntry("Not yet implemented in CLI");
                System.exit(1);
                break;
            case NEW_RELIC_DEPLOYMENT:
                logger.addErrorLogEntry("Not yet implemented in CLI");
                System.exit(1);
                break;
            case S3_CREATE:
                logger.addErrorLogEntry("Not yet implemented in CLI");
                System.exit(1);
                break;
        }
        return null;
    }
}