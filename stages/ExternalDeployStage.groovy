import com.epam.edp.stages.impl.cd.Stage
import org.apache.commons.lang.RandomStringUtils

@Stage(name = "deploy-helm")
class ExternalDeployStage {
    Script script

    def checkHelmExists(context) {
        if (!script.sh(script: "helm version", returnStdout: true)) {
            script.println("Failed")
            return false
        }
        script.println("Success")
        return true
    }

    def getBuildUserFromLog(context) {
        def jenkinsCred = "admin:${context.jenkins.token}".bytes.encodeBase64().toString()
        def jobUrl = "${context.job.buildUrl}".replaceFirst("${context.job.jenkinsUrl}", '')
        def response = script.httpRequest url: "http://jenkins.${context.job.ciProject}:8080/${jobUrl}consoleText",
                httpMode: 'GET',
                customHeaders: [[name: 'Authorization', value: "Basic ${jenkinsCred}"]]
        return script.sh(
                script: "#!/bin/sh -e\necho \"${response.content}\" | grep \"Approved by\" -m 1 | awk {'print \$3'}",
                returnStdout: true
        ).trim()
    }

    def checkOpenshiftTemplateExists(context, templateName) {
        if (!script.openshift.selector("template", templateName).exists()) {
            script.println("[JENKINS][WARNING] Template which called ${templateName} doesn't exist in ${context.job.ciProject} namespace")
            return false
        }
        return true
    }

    def deployConfigMaps(codebaseDir, name, context) {
        File folder = new File("${codebaseDir}/config-files")
        for (file in folder.listFiles()) {
            if (file.isFile() && file.getName() == "Readme.md")
                continue
            String configsDir = file.getName().split("\\.")[0].replaceAll("[^\\p{L}\\p{Nd}]+", "-").toLowerCase()
            context.platform.createConfigMapFromFile("${name}-${configsDir}", context.job.deployProject, "${codebaseDir}/config-files/${file.getName()}")
            script.println("[JENKINS][DEBUG] Configmap ${configsDir} has been created")
        }
    }

    def checkDeployment(context, object, type) {
        script.println("[JENKINS][DEBUG] Validate deployment - ${object.name} in ${context.job.deployProject}")
        try {
            context.platform.verifyDeployedCodebase(object.name, context.job.deployProject)
            if (type == 'application' && getDeploymentVersion(context, object) != object.currentDeploymentVersion) {
                script.println("[JENKINS][DEBUG] Deployment ${object.name} in project ${context.job.deployProject} has been rolled out")
            } else
                script.println("[JENKINS][DEBUG] New version of codebase ${object.name} hasn't been deployed, because the save version")
        }
        catch (Exception verifyDeploymentException) {
            script.println("[JENKINS][WARNING] Deployment of ${object.name} failed.Reason:\r\n ${verifyDeploymentException}")
            if (type == "application" && object.currentDeploymentVersion != 0) {
                script.println("[JENKINS][WARNING] Rolling out of ${object.name} with version ${object.version} has been failed.\r\n" +
                        "[JENKINS][WARNING] Rolling back to the previous version")
                context.platform.rollbackDeployedCodebase(object.name, context.job.deployProject)
                context.platform.verifyDeployedCodebase(object.name, context.job.deployProject)
                script.println("[JENKINS][WARNING] Rolling out of ${object.name} with version ${object.version} has been failed.")
            } else
                script.println("[JENKINS][WARNING] ${object.name} deploy has been failed. Reason - ${verifyDeploymentException}")
        }

    }

    def getDeploymentVersion(context, codebase) {
        if (!context.platform.checkObjectExists("deployment", codebase.name, context.job.deployProject)) {
            script.println("[JENKINS][WARNING] Deployment ${codebase.name} doesn't exist in the project ${context.job.deployProject}\r\n" +
                    "[JENKINS][WARNING] We will roll it out")
            return null
        }
        def version = context.platform.getJsonPathValue("deployment", codebase.name, ".status.latestVersion", context.job.deployProject)
        return (version.toInteger())
    }

    def checkImageExists(context, object) {
        def imageExists = context.platform.getImageStream(object.inputIs, context.job.crApiVersion)
        if (imageExists == "") {
            script.println("[JENKINS][WARNING] Image stream ${object.name} doesn't exist in the project ${context.job.ciProject}\r\n" +
                    "[JENKINS][WARNING] Deploy will be skipped")
            return false
        }

        def tagExist = context.platform.getImageStreamTags(object.inputIs, context.job.crApiVersion)
        if (!tagExist) {
            script.println("[JENKINS][WARNING] Image stream ${object.name} with tag ${object.version} doesn't exist in the project ${context.job.ciProject}\r\n" +
                    "[JENKINS][WARNING] Deploy will be skipped")
            return false
        }
        return true
    }

    def getRepositoryPath(codebase) {
        if (codebase.strategy == "import") {
            return codebase.gitProjectPath
        }
        return "/" + codebase.name
    }

    def cloneProject(context, codebase) {
        script.println("[JENKINS][DEBUG] Start fetching Git Server info for ${codebase.name} from ${codebase.gitServer} CR")

        def gitServerName = "gitservers.${context.job.crApiVersion}.edp.epam.com"

        script.println("[JENKINS][DEBUG] Git Server CR Version: ${context.job.crApiVersion}")
        script.println("[JENKINS][DEBUG] Git Server Name: ${gitServerName}")

        def autouser = context.platform.getJsonPathValue(gitServerName, codebase.gitServer, ".spec.gitUser")
        def host = context.platform.getJsonPathValue(gitServerName, codebase.gitServer, ".spec.gitHost")
        def sshPort = context.platform.getJsonPathValue(gitServerName, codebase.gitServer, ".spec.sshPort")
        def credentialsId = context.platform.getJsonPathValue(gitServerName, codebase.gitServer, ".spec.nameSshKeySecret")

        script.println("[JENKINS][DEBUG] autouser: ${autouser}")
        script.println("[JENKINS][DEBUG] host: ${host}")
        script.println("[JENKINS][DEBUG] sshPort: ${sshPort}")
        script.println("[JENKINS][DEBUG] credentialsId: ${credentialsId}")

        def repoPath = getRepositoryPath(codebase)
        script.println("[JENKINS][DEBUG] Repository path: ${repoPath}")

        def gitCodebaseUrl = "ssh://${autouser}@${host}:${sshPort}${repoPath}"

        try {
            script.checkout([$class                           : 'GitSCM', branches: [[name: "refs/tags/${codebase.version}"]],
                             doGenerateSubmoduleConfigurations: false, extensions: [],
                             submoduleCfg                     : [],
                             userRemoteConfigs                : [[credentialsId: "${credentialsId}",
                                                                  refspec      : "refs/tags/${codebase.version}",
                                                                  url          : "${gitCodebaseUrl}"]]])
        }
        catch (Exception ex) {
            script.println("[JENKINS][WARNING] Project ${codebase.name} cloning has failed with ${ex}\r\n" +
                    "[JENKINS][WARNING] Deploy will be skipped\r\n" +
                    "[JENKINS][WARNING] Check if tag ${codebase.version} exists in repository")
            script.currentBuild.result = 'UNSTABLE'
            script.currentBuild.description = "${script.currentBuild.description}\r\n${codebase.name} deploy failed"
            return false
        }
        script.println("[JENKINS][DEBUG] Project ${codebase.name} has been successfully cloned")
        return true
    }

    def getDockerRegistryInfo(context) {
        try {
            return context.platform.getJsonPathValue("edpcomponents", "docker-registry", ".spec.url")
        }
        catch (Exception ex) {
            script.println("[JENKINS][WARNING] Getting docker registry info failed.Reason:\r\n ${ex}")
            return null
        }
    }

    def deployCodebaseTemplate(context, codebase, deployTemplatesPath) {
        def templateName = "Chart"
        if (!checkTemplateExists(templateName, deployTemplatesPath)) {
            return
        }

        def valuesFileName = "values-${context.job.stageName}"
        def valuesFilePath = "${deployTemplatesPath}/values-${context.job.stageName}.yaml"
        if (!checkTemplateExists(valuesFileName, deployTemplatesPath)) {
            valuesFilePath = "${deployTemplatesPath}/values.yaml"
            script.println("[JENKINS][DEBUG] ${context.job.stageName} environment specific values not found, default values will be used: ${valuesFilePath}")
        }

        codebase.cdPipelineName = context.job.pipelineName
        codebase.cdPipelineStageName = context.job.stageName

        def imageName = codebase.inputIs ? codebase.inputIs : codebase.normalizedName
        def parametersMap = [
                ['name': 'namespace', 'value': "${context.job.deployProject}"],
                ['name': 'cdPipelineName', 'value': "${codebase.cdPipelineName}"],
                ['name': 'cdPipelineStageName', 'value': "${codebase.cdPipelineStageName}"],
                ['name': 'image.name', 'value': "${context.environment.config.dockerRegistryHost}/${imageName}"],
                ['name': 'image.version', 'value': "${codebase.version}"],
                ['name': 'database.required', 'value': "${codebase.db_kind != "" ? true : false}"],
                ['name': 'database.version', 'value': "${codebase.db_version}"],
                ['name': 'database.capacity', 'value': "${codebase.db_capacity}"],
                ['name': 'database.database.storageClass', 'value': "${codebase.db_storage}"],
                ['name': 'ingress.path', 'value': "${codebase.route_path}"],
                ['name': 'ingress.site', 'value': "${codebase.route_site}"],
                ['name': 'dnsWildcard', 'value': "${context.job.dnsWildcard}"],
        ]

        context.platform.deployCodebase(
                context.job.deployProject,
                "${deployTemplatesPath}",
                codebase,
                "${context.environment.config.dockerRegistryHost}/${imageName}",
                context.job.deployTimeout,
                parametersMap,
                valuesFilePath
        )
    }

    def checkTemplateExists(templateName, deployTemplatesPath) {
        def templateYamlFile = new File("${deployTemplatesPath}/${templateName}.yaml")
        if (!templateYamlFile.exists()) {
            script.println("[JENKINS][WARNING] Template file which called ${templateName}.yaml doesn't exist in ${deployTemplatesPath} in the repository")
            return false
        }
        return true
    }

    def deployCodebase(version, name, context, codebase) {
        def codebaseDir = "${script.WORKSPACE}/${RandomStringUtils.random(10, true, true)}/${name}"
        def deployTemplatesPath = "${codebaseDir}/${context.job.deployTemplatesDirectory}"
        script.dir("${codebaseDir}") {
            if (!cloneProject(context, codebase)) {
                if (codebase.name in context.job.applicationsToPromote)
                    context.job.applicationsToPromote.remove(codebase.name)
                return
            }
            script.withCredentials([script.string(credentialsId: "${context.job.stageName}.url", variable: "serverUrl")]) {
                script.withKubeConfig([credentialsId: "${context.job.stageName}.token", serverUrl: "${script.serverUrl}"]) {
                    deployConfigMaps(codebaseDir, name, context)
                    try {
                        deployCodebaseTemplate(context, codebase, deployTemplatesPath)
                    }
                    catch (Exception ex) {
                        script.println("[JENKINS][WARNING] Deployment of codebase ${name} has been failed. Reason - ${ex}.")
                        script.currentBuild.result = 'UNSTABLE'
                        context.platform.rollbackDeployedCodebase(codebase.name, context.job.deployProject)
                        if (codebase.name in context.job.applicationsToPromote)
                            context.job.applicationsToPromote.remove(codebase.name)
                    }
                }
            }
        }
    }

    def getNElements(entities, max_apps) {
        def tempEntityList = entities.stream()
                .limit(max_apps.toInteger())
                .collect()
        entities.removeAll(tempEntityList)

        return tempEntityList
    }

    void run(context) {
        script.withCredentials([script.string(credentialsId: "${context.job.stageName}.url", variable: "serverUrl")]) {
            script.withKubeConfig([credentialsId: "${context.job.stageName}.token", serverUrl: "${script.serverUrl}"]) {
                context.platform.createProjectIfNotExist(context.job.deployProject, context.job.edpName)
                script.sh "kubectl label namespace ${context.job.deployProject} azure-key-vault-env-injection=enabled --overwrite"
                def secretSelector = context.platform.getObjectList("secret")
                script.println("[JENKINS][DEBUG] Shared secrets found: ${secretSelector}")

                secretSelector.each() { secret ->
                    def newSecretName = secret.replace(context.job.sharedSecretsMask, '')
                    if (secret =~ /${context.job.sharedSecretsMask}/)
                        if (!context.platform.checkObjectExists('secrets', newSecretName))
                            context.platform.copySharedSecrets(secret, newSecretName, context.job.deployProject)
                }
            }
        }

        if (context.job.buildUser == null || context.job.buildUser == "")
            context.job.buildUser = getBuildUserFromLog(context)

        if (context.job.buildUser != null && context.job.buildUser != "") {
            context.platform.createRoleBinding(context.job.buildUser, context.job.deployProject)
        }

        def deployCodebasesList = context.job.codebasesList.clone()
        while (!deployCodebasesList.isEmpty()) {
            def parallelCodebases = [:]
            def tempAppList = getNElements(deployCodebasesList, context.job.maxOfParallelDeployApps)

            tempAppList.each() { codebase ->
                if ((codebase.version == "No deploy") || (codebase.version == "noImageExists")) {
                    script.println("[JENKINS][WARNING] Application ${codebase.name} deploy skipped")
                    return
                }

                if (codebase.version == "latest") {
                    codebase.version = codebase.latest
                    script.println("[JENKINS][DEBUG] Latest tag equals to ${codebase.latest} version")
                    if (!codebase.version)
                        return
                }

                if (codebase.version == "stable") {
                    codebase.version = codebase.stable
                    script.println("[JENKINS][DEBUG] Stable tag equals to ${codebase.stable} version")
                    if (!codebase.version)
                        return
                }

                if (!checkImageExists(context, codebase))
                    return

                context.environment.config.dockerRegistryHost = getDockerRegistryInfo(context)
                script.withCredentials([script.string(credentialsId: "${context.job.stageName}.dnsname", variable: "dnsName")]) {
                    context.job.dnsWildcard = script.dnsName
                }
                parallelCodebases["${codebase.name}"] = {
                    deployCodebase(codebase.version, codebase.name, context, codebase)
                }
            }
            script.parallel parallelCodebases
        }
    }
}
return ExternalDeployStage
