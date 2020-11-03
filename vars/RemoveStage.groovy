/* Copyright 2019 EPAM Systems.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

See the License for the specific language governing permissions and
limitations under the License.*/

import com.epam.edp.removepipes.database.Database
import com.epam.edp.removepipes.k8s.k8sResource
import com.epam.edp.removepipes.jenkins.JenkinsItem

def call() {
    def context = [:]

    node("master") {
        stage("Init") {
            final String EDP_DEPLOY_PROJECT = params.PROJECT_NAME

            if (params.RESOURCES_VERSION_2) {
                context.stageCR = "stage.v2"
            } else {
                context.stageCR = "stage"
            }

            context.projectName = params.PROJECT_NAME

            context.database = new Database(EDP_DEPLOY_PROJECT, this)
            context.database.init()

            if (!params.INTERACTIVE_MODE) {
                context.cdPipeline = params.CD_PIPELINE
                context.cdStage = params.CD_STAGE

                if (context.database.getCdPipelines(context.projectName).contains(context.cdPipeline)) {
                    if (!context.database.getCdStages(context.projectName, context.cdPipeline).contains(context.cdStage))
                        error "CD stage \"${context.cdStage}\" not found in the \"${context.cdPipeline}\" CD pipeline"

                } else {
                    error "CD pipeline \"${context.cdPipeline}\" not found in the \"${context.projectName}\" project"
                }

            } else {
                def cdPipelineChoices = []
                def cdStageChoices = []

                cdPipelineChoices.add(choice(choices: "${context.database.getCdPipelines(context.projectName).plus('No_deletion').join('\n')}", name: "CD_PIPELINE"))
                context.cdPipeline = input id: 'cdPipeline', message: 'CD pipeline you want to remove stage in.',
                        parameters: cdPipelineChoices

                if (context.cdPipeline == "No_deletion")
                    error "Deletion aborted"

                cdStageChoices.add(choice(choices: "${context.database.getCdStages(context.projectName, context.cdPipeline).plus('No_deletion').join('\n')}", name: "CD_STAGE"))
                context.cdStage = input id: 'cdStage', message: 'CD stage you want to remove.',
                        parameters: cdStageChoices

                if (context.cdStage == "No_deletion")
                    error "Deletion aborted"
            }
        }
        stage("Remove stage custom resource") {
            new k8sResource(context.stageCR, "${context.cdPipeline}-${context.cdStage}", this).remove()
        }
        stage("Remove database entries") {
            context.database.removeCdStage(context.projectName, context.cdPipeline, context.cdStage, params.RESOURCES_VERSION_2)
        }
        stage("Remove Jenkins deploy job") {
            new JenkinsItem("${context.cdPipeline}-cd-pipeline/${context.cdStage}", this).remove()
        }
        stage("Remove codebase image streams") {
            context.database.getCdPipelineApplications(context.projectName, context.cdPipeline, params.RESOURCES_VERSION_2).each { application ->
                new k8sResource("codebaseimagestream", "${context.cdPipeline}-${context.cdStage}-${application}-verified",
                        this).remove()
            }
            stage("Remove namespace from k8s") {
                if (params.DELETE_NAMESPACE) {
                    new k8sResource("ns", "${context.projectName}-${context.cdPipeline}-${context.cdStage}", this).remove()
                } else
                    println("Namespace \"${context.projectName}-${context.cdPipeline}-${context.cdStage}\" is not removed")
            }
        }
    }
}
