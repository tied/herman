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
package com.libertymutualgroup.herman.aws.lambda;

import static java.lang.Math.toIntExact;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.SecurityGroup;
import com.amazonaws.services.ec2.model.Subnet;
import com.amazonaws.services.ec2.model.Vpc;
import com.amazonaws.services.ecs.model.KeyValuePair;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagement;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClientBuilder;
import com.amazonaws.services.identitymanagement.model.Role;
import com.amazonaws.services.kms.AWSKMS;
import com.amazonaws.services.kms.AWSKMSClientBuilder;
import com.amazonaws.services.kms.model.Tag;
import com.amazonaws.services.lambda.AWSLambda;
import com.amazonaws.services.lambda.AWSLambdaClientBuilder;
import com.amazonaws.services.lambda.model.AddPermissionRequest;
import com.amazonaws.services.lambda.model.CreateFunctionRequest;
import com.amazonaws.services.lambda.model.CreateFunctionResult;
import com.amazonaws.services.lambda.model.Environment;
import com.amazonaws.services.lambda.model.FunctionCode;
import com.amazonaws.services.lambda.model.GetFunctionConfigurationRequest;
import com.amazonaws.services.lambda.model.GetFunctionConfigurationResult;
import com.amazonaws.services.lambda.model.GetFunctionRequest;
import com.amazonaws.services.lambda.model.GetFunctionResult;
import com.amazonaws.services.lambda.model.RemovePermissionRequest;
import com.amazonaws.services.lambda.model.ResourceNotFoundException;
import com.amazonaws.services.lambda.model.TagResourceRequest;
import com.amazonaws.services.lambda.model.UpdateFunctionCodeRequest;
import com.amazonaws.services.lambda.model.UpdateFunctionConfigurationRequest;
import com.amazonaws.services.lambda.model.UpdateFunctionConfigurationResult;
import com.amazonaws.services.lambda.model.VpcConfig;
import com.amazonaws.util.IOUtils;
import com.atlassian.bamboo.build.logger.BuildLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.libertymutualgroup.herman.aws.AwsExecException;
import com.libertymutualgroup.herman.aws.CredentialsHandler;
import com.libertymutualgroup.herman.aws.ecs.PushType;
import com.libertymutualgroup.herman.aws.ecs.broker.iam.IAMBroker;
import com.libertymutualgroup.herman.aws.ecs.broker.kms.KmsBroker;
import com.libertymutualgroup.herman.task.common.CommonTaskProperties;
import com.libertymutualgroup.herman.util.ArnUtil;
import com.libertymutualgroup.herman.util.AwsNetworkingUtil;
import com.libertymutualgroup.herman.util.FileUtil;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LambdaBroker {

    private static final Logger LOGGER = LoggerFactory.getLogger(LambdaBroker.class);
    private static final String LAMBDA_TEMPLATE_JSON = "lambda_template.json";
    private static final String LAMBDA_TEMPLATE_YML = "lambda_template.yml";
    private static final String LAMBDA_EXECUTION_PERMISSION = "lambda-execution-permission.json";

    private LambdaPushContext context;
    private BuildLogger buildLogger;

    private FileUtil fileUtil;
    private LambdaInjectConfiguration configuration;

    private AWSLambda lambdaClient;
    private AWSKMS kmsClient;
    private AmazonIdentityManagement iamClient;
    private AwsNetworkingUtil networkingUtil;
    private CommonTaskProperties taskProperties;

    public LambdaBroker(LambdaPushContext context, BuildLogger buildLogger, Regions region) {
        this.context = context;
        this.buildLogger = buildLogger;
        this.fileUtil = new FileUtil(this.context.getRootPath(), this.buildLogger);
        this.configuration = getLambdaInjectConfiguration(this.context, fileUtil);
        this.taskProperties = context.getTaskProperties();

        AWSCredentials credentials = this.context.getSessionCredentials();
        ClientConfiguration config = CredentialsHandler.getConfiguration();

        this.lambdaClient = AWSLambdaClientBuilder.standard()
            .withCredentials(new AWSStaticCredentialsProvider(credentials))
            .withClientConfiguration(config)
            .withRegion(region)
            .build();

        this.iamClient = AmazonIdentityManagementClientBuilder.standard()
            .withCredentials(new AWSStaticCredentialsProvider(credentials))
            .withClientConfiguration(config)
            .withRegion(region)
            .build();

        this.kmsClient = AWSKMSClientBuilder.standard()
            .withCredentials(new AWSStaticCredentialsProvider(credentials))
            .withClientConfiguration(config)
            .withRegion(region)
            .build();

        AmazonEC2 ec2Client = AmazonEC2ClientBuilder.standard()
            .withCredentials(new AWSStaticCredentialsProvider(credentials)).withClientConfiguration(config)
            .withRegion(region).build();
        this.networkingUtil = new AwsNetworkingUtil(ec2Client);
    }

    public void brokerLambda() throws IOException {
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag().withTagKey(this.taskProperties.getSbuTagKey()).withTagValue(this.taskProperties.getSbu()));
        tags.add(new Tag().withTagKey(this.taskProperties.getOrgTagKey()).withTagValue(this.taskProperties.getOrg()));
        tags.add(
            new Tag().withTagKey(this.taskProperties.getAppTagKey()).withTagValue(this.configuration.getAppName()));
        Map<String, String> tagMap = tags.stream().collect(Collectors.toMap(Tag::getTagKey, Tag::getTagValue));

        String functionArn;
        try {
            GetFunctionConfigurationResult describeResult = lambdaClient.getFunctionConfiguration(
                new GetFunctionConfigurationRequest().withFunctionName(this.configuration.getFunctionName())
            );
            functionArn = describeResult.getFunctionArn();
        } catch (ResourceNotFoundException ex) {
            LOGGER.debug("Function not found: " + this.configuration.getFunctionName(), ex);
            functionArn = null;
        }

        this.buildLogger
            .addBuildLogEntry("Brokering execution role with name: " + this.configuration.getFunctionName());

        String iamPolicyFileName = Optional.ofNullable(this.configuration.getIamPolicy()).orElse("iam-policy.json");
        String defaultExecutionRole = IOUtils
            .toString(this.getClass().getResourceAsStream("/iam/lambda-default-execution-policy.json"));
        String policy = this.fileUtil.findFile(iamPolicyFileName, true);
        if (policy == null) {
            policy = defaultExecutionRole;
        }
        IAMBroker iamBroker = new IAMBroker(this.buildLogger);
        Role executionRole = iamBroker
            .brokerAppRole(this.iamClient, this.configuration, policy, this.context.getBambooPropertyHandler(),
                PushType.LAMBDA);

        this.context.getBambooPropertyHandler().addProperty("app.iam", executionRole.getArn());
        this.context.getBambooPropertyHandler()
            .addProperty("account.id", ArnUtil.getAccountFromArn(executionRole.getArn()));

        FunctionCode functionCode;

        try {
            FileInputStream zipInputStream = fileUtil.findfileAsInputStream(this.configuration.getZipFileName());
            FileChannel zipInputChannel = zipInputStream.getChannel();
            ByteBuffer code = ByteBuffer.allocate(toIntExact(zipInputChannel.size()));
            zipInputChannel.read(code);
            code.rewind();
            functionCode = new FunctionCode().withZipFile(code);
            zipInputChannel.close();
        } catch (IOException ex) {
            buildLogger.addBuildLogEntry("Failed to read zip file: " + this.configuration.getZipFileName());
            throw new AwsExecException(ex);
        }

        VpcConfig vpcConfig = new VpcConfig();
        Vpc vpc;
        if (this.configuration.getVpcId() != null) {
            vpc = this.networkingUtil.getVpc(this.configuration.getVpcId());
            if (vpc == null) {
                throw new AwsExecException("Unable to find VPC");
            }
            List<Subnet> subnets = this.networkingUtil.getSubnets();
            List<String> subnetIds = subnets.stream().map(Subnet::getSubnetId).collect(Collectors.toList());
            List<String> securityGroupIds = this.networkingUtil
                .findSecurityGroupsByClusterName(vpc, this.configuration.getVpcId())
                .stream().map(SecurityGroup::getGroupId).collect(Collectors.toList());

            vpcConfig.setSubnetIds(subnetIds);
            vpcConfig.setSecurityGroupIds(securityGroupIds);
        }

        HashMap<String, String> environmentMap = new HashMap<>();
        if (this.configuration.getEnvironment() != null) {
            for (KeyValuePair envVar : this.configuration.getEnvironment()) {
                environmentMap.put(envVar.getName(), envVar.getValue());
            }
        }
        Environment environment = new Environment().withVariables(environmentMap);

        this.buildLogger.addBuildLogEntry("VPC Configuration: " + vpcConfig);

        LambdaPermission permission = getExecutionPermission();

        AddPermissionRequest permissionRequest = null;
        if (permission != null) {
            permissionRequest = new AddPermissionRequest()
                .withFunctionName(this.configuration.getFunctionName())
                .withAction(permission.action)
                .withPrincipal(permission.principal)
                .withEventSourceToken(permission.eventSourceToken)
                .withStatementId(this.configuration.getFunctionName() + "-InvokePermission")
                .withQualifier(permission.qualifier)
                .withSourceArn(permission.sourceArn);
        }

        String kmsKeyArn = brokerKms(tags);

        if (functionArn == null && functionCode != null) {
            buildLogger.addBuildLogEntry("Pushing new Lambda");

            CreateFunctionRequest createRequest = new CreateFunctionRequest()
                .withCode(functionCode)
                .withFunctionName(this.configuration.getFunctionName())
                .withHandler(this.configuration.getHandler())
                .withRole(executionRole.getArn())
                .withRuntime(this.configuration.getRuntime())
                .withTags(tagMap)
                .withTimeout(this.configuration.getTimeout())
                .withVpcConfig(vpcConfig)
                .withMemorySize(this.configuration.getMemorySize())
                .withEnvironment(environment)
                .withKMSKeyArn(kmsKeyArn);

            CreateFunctionResult result = lambdaClient.createFunction(createRequest);
            buildLogger.addBuildLogEntry("Lambda created: " + result.getFunctionName());
        } else if (functionArn != null && functionCode != null) {
            buildLogger.addBuildLogEntry("Lambda exists, attempting update...");

            UpdateFunctionCodeRequest updateFunctionCode = new UpdateFunctionCodeRequest()
                .withFunctionName(this.configuration.getFunctionName())
                .withZipFile(functionCode.getZipFile());

            UpdateFunctionConfigurationRequest updateFunctionConfiguration = new UpdateFunctionConfigurationRequest()
                .withFunctionName(this.configuration.getFunctionName())
                .withHandler(this.configuration.getHandler())
                .withRole(executionRole.getArn())
                .withRuntime(this.configuration.getRuntime())
                .withTimeout(this.configuration.getTimeout())
                .withVpcConfig(vpcConfig)
                .withMemorySize(this.configuration.getMemorySize())
                .withEnvironment(environment)
                .withKMSKeyArn(kmsKeyArn);

            TagResourceRequest tagRequest = new TagResourceRequest()
                .withResource(functionArn)
                .withTags(tagMap);

            buildLogger.addBuildLogEntry("Updating with configuration: " + updateFunctionConfiguration);
            lambdaClient.updateFunctionCode(updateFunctionCode);
            UpdateFunctionConfigurationResult configurationResult = lambdaClient
                .updateFunctionConfiguration(updateFunctionConfiguration);
            lambdaClient.tagResource(tagRequest);

            buildLogger.addBuildLogEntry("Lambda updated: " + configurationResult.getFunctionName());
        }

        if (permissionRequest != null) {
            try {
                buildLogger.addBuildLogEntry("Restting execution permissions");

                RemovePermissionRequest removeExistingPerms = new RemovePermissionRequest()
                    .withFunctionName(this.configuration.getFunctionName())
                    .withStatementId(permissionRequest.getStatementId());
                lambdaClient.removePermission(removeExistingPerms);
            } catch (ResourceNotFoundException ex) {
                LOGGER.debug("Function not found: " + this.configuration.getFunctionName(), ex);
                buildLogger.addBuildLogEntry("Unable to reset permissions, skipping...");
            }

            buildLogger.addBuildLogEntry("Adding new execution permission");
            lambdaClient.addPermission(permissionRequest);
        }

        buildLogger.addBuildLogEntry("Lambda pushed");
        GetFunctionResult output = lambdaClient
            .getFunction(new GetFunctionRequest().withFunctionName(this.configuration.getFunctionName()));
        if (this.configuration.getUseKms()) {
            buildLogger.addBuildLogEntry("Pushed lambda with kms key " + output.getConfiguration().getKMSKeyArn());
        }
        buildLogger.addBuildLogEntry(output.getConfiguration().toString());
    }

    private LambdaPermission getExecutionPermission() {
        try {
            if (this.fileUtil.fileExists(LAMBDA_EXECUTION_PERMISSION)) {
                this.buildLogger
                    .addBuildLogEntry(String.format("Using %s for execution permissions", LAMBDA_EXECUTION_PERMISSION));
                String template = fileUtil.findFile(LAMBDA_EXECUTION_PERMISSION, false);
                ObjectMapper mapper = new ObjectMapper();
                return mapper.readValue(this.context.getBambooPropertyHandler().mapInProperties(template),
                    LambdaPermission.class);
            }
        } catch (IOException ex) {
            LOGGER.debug("Error getting execution permissions", ex);
            return null;
        }

        return null;
    }

    private String brokerKms(List<Tag> tags) {
        KmsBroker kmsBroker = new KmsBroker(this.context.getLogger(), this.context.getBambooPropertyHandler(),
            this.fileUtil, this.context.getTaskProperties());
        String keyArn = "";
        if (this.configuration.getUseKms()) {
            String keyId = kmsBroker.brokerKey(kmsClient, this.configuration, tags);
            keyArn = kmsBroker.getExistingKeyArnFromId(kmsClient, keyId);
        } else {
            kmsBroker.deleteKey(kmsClient, this.configuration);
        }

        return keyArn;
    }

    private LambdaInjectConfiguration getLambdaInjectConfiguration(LambdaPushContext context, FileUtil fileUtil) {
        LambdaInjectConfiguration definition;
        String template;

        boolean isJson;

        try {
            if (new File(context.getRootPath() + File.separator + LAMBDA_TEMPLATE_JSON).exists()) {
                buildLogger.addBuildLogEntry("Using " + LAMBDA_TEMPLATE_JSON);
                template = fileUtil.findFile(LAMBDA_TEMPLATE_JSON, false);
                isJson = true;
            } else if (new File(context.getRootPath() + File.separator + LAMBDA_TEMPLATE_YML).exists()) {
                buildLogger.addBuildLogEntry("Using " + LAMBDA_TEMPLATE_YML);
                template = fileUtil.findFile(LAMBDA_TEMPLATE_YML, false);
                isJson = false;
            } else {
                throw new AwsExecException("No Lambda template provided!");
            }

            ObjectMapper mapper = isJson ? new ObjectMapper() : new ObjectMapper(new YAMLFactory());

            definition = mapper.readValue(this.context.getBambooPropertyHandler().mapInProperties(template),
                LambdaInjectConfiguration.class);
        } catch (FileNotFoundException e1) {
            LOGGER.debug("No template found: " + LAMBDA_TEMPLATE_JSON, e1);
            throw new AwsExecException("No template found at " + LAMBDA_TEMPLATE_JSON);
        } catch (IOException e1) {
            throw new AwsExecException(e1);
        }

        return definition;
    }
}