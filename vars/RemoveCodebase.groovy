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
                context.codebaseBranchCR = "codebasebranch.v2"
                context.codebaseCR = "codebase.v2"
            } else {
                context.codebaseBranchCR = "codebasebranch"
                context.codebaseCR = "codebase"
            }

            context.projectName = params.PROJECT_NAME

            context.database = new Database(EDP_DEPLOY_PROJECT, this)
            context.database.init()

            if (!params.INTERACTIVE_MODE) {
                context.codebase = params.CODEBASE

                if (!context.database.getCodebases(context.projectName).contains(context.codebase))
                    error "Codebase \"${context.codebase}\" not found in the \"${context.projectName}\" project"

            } else {
                def codebaseChoices = []

                codebaseChoices.add(choice(choices: "${context.database.getCodebases(context.projectName).plus('No_deletion').join('\n')}", name: "CODEBASE"))
                context.codebase = input id: 'application', message: 'Codebase you want to remove.',
                        parameters: codebaseChoices

                if (context.codebase == "No_deletion")
                    error "Deletion aborted"
            }
            context.codebaseType = context.database.getCodebaseType(context.projectName, context.codebase)
        }
        stage("Check that codebase is not in use") {
            ArrayList cdPipelines
            String errorMessage
            switch (context.codebaseType) {
                case "autotests":
                    cdPipelines = context.database.getAutotestsCdPipelines(context.projectName, context.codebase, params.RESOURCES_VERSION_2)
                    errorMessage = "Autotests \"${context.codebase}\" cannot be removed while CD pipelines use it."
                    break
                case "application":
                    cdPipelines = context.database.getApplicationCdPipelines(context.projectName, context.codebase, params.RESOURCES_VERSION_2)
                    errorMessage = "Application \"${context.codebase}\" cannot be removed while CD pipelines use it."
                    break
                case "library":
                    cdPipelines = context.database.getLibraryCdPipelines(context.projectName, context.codebase, params.RESOURCES_VERSION_2)
                    errorMessage = "Library \"${context.codebase}\" cannot be removed while CD pipelines use it."
                    break
                default:
                    break
            }
            if (cdPipelines.size() != 0) {
                println "Found ${context.codebaseType} \"${context.codebase}\" usage in CD pipelines: ${cdPipelines.unique()}"
                error errorMessage
            }
        }
        stage("Remove codebase branches") {
            context.database.getCodebaseBranches(context.projectName, context.codebase).each { branch ->
                new k8sResource(context.codebaseBranchCR, "${context.codebase}-${branch}", this).remove()
                context.database.removeCodebaseBranch(context.projectName, context.codebase, branch, params.RESOURCES_VERSION_2)
                if (context.codebaseType != "autotests") {
                    new k8sResource("codebaseimagestream", "${context.codebase}-${branch}", this).remove()
                }
            }
        }
        stage("Remove custom resources") {
            new k8sResource(context.codebaseCR, context.codebase, this).remove()
        }
        stage("Remove database entries") {
            context.database.removeCodebase(context.projectName, context.codebase, params.RESOURCES_VERSION_2)
        }
        stage("Remove Jenkins folder") {
            new JenkinsItem(context.codebase, this).remove()
        }
    }
}
