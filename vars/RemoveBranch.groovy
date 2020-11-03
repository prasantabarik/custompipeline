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
import com.epam.edp.removepipes.jenkins.JenkinsView

def call() {
    def context = [:]

    node("master") {
        stage("Init") {
            final String EDP_DEPLOY_PROJECT = params.PROJECT_NAME

            if (params.RESOURCES_VERSION_2) {
                context.codebaseBranchCR = "codebasebranch.v2"
            } else {
                context.codebaseBranchCR = "codebasebranch"
            }

            context.projectName = params.PROJECT_NAME

            context.database = new Database(EDP_DEPLOY_PROJECT, this)
            context.database.init()

            if (!params.INTERACTIVE_MODE) {
                context.codebase = params.CODEBASE
                context.codebaseBranch = params.CODEBASE_BRANCH

                if (context.codebaseBranch == "master")
                    error "Branch \"master\" is protected from removing and can be removed only with the whole codebase."

                if (context.database.getCodebases(context.projectName).contains(context.codebase)) {
                    if (!context.database.getCodebaseBranches(context.projectName, context.codebase).contains(context.codebaseBranch))
                        error "Codebase branch \"${context.codebaseBranch}\" not found in the \"${context.codebase}\" codebase"

                } else {
                    error "Codebase \"${context.codebase}\" not found in the \"${context.projectName}\" project"
                }

            } else {
                def codebaseChoices = []
                def codebaseBranchChoices = []

                codebaseChoices.add(choice(choices: "${context.database.getCodebases(context.projectName).plus('No_deletion').join('\n')}", name: "CODEBASE"))
                context.codebase = input id: 'application', message: 'Codebase you want to remove branch in.',
                        parameters: codebaseChoices

                if (context.codebase == "No_deletion")
                    error "Deletion aborted"

                codebaseBranchChoices.add(choice(choices: "${context.database.getCodebaseBranches(context.projectName, context.codebase).plus('No_deletion').join('\n').minus('master\n')}", name: "CODEBASE_BRANCH"))
                context.codebaseBranch = input id: 'codebaseBranch', message: 'Codebase branch you want to remove.',
                        parameters: codebaseBranchChoices

                if (context.codebaseBranch == "No_deletion")
                    error "Deletion aborted"
            }
            context.codebaseType = context.database.getCodebaseType(context.projectName, context.codebase)
        }
        stage("Check that codebase branch is not in use") {
            ArrayList cdPipelines
            String errorMessage
            switch (context.codebaseType) {
                case "autotests":
                    cdPipelines = context.database.getAutotestsBranchCdPipelines(context.projectName, context.codebase, context.codebaseBranch, params.RESOURCES_VERSION_2)
                    errorMessage = "Autotests \"${context.codebase}\" branch \"${context.codebaseBranch}\" cannot be removed while CD pipelines use it."
                    break
                case "application":
                    cdPipelines = context.database.getApplicationBranchCdPipelines(context.projectName, context.codebase, context.codebaseBranch, params.RESOURCES_VERSION_2)
                    errorMessage = "Application \"${context.codebase}\" branch \"${context.codebaseBranch}\" cannot be removed while CD pipelines use it."
                    break
                case "library":
                    cdPipelines = context.database.getLibraryBranchCdPipelines(context.projectName, context.codebase, context.codebaseBranch, params.RESOURCES_VERSION_2)
                    errorMessage = "Library \"${context.codebase}\" branch \"${context.codebaseBranch}\" cannot be removed while CD pipelines use it."
                    break
                default:
                    break
            }
            if (cdPipelines.size() != 0) {
                println "Found ${context.codebaseType} \"${context.codebase}\" branch \"${context.codebaseBranch}\" usage in CD pipelines - ${cdPipelines.unique()}"
                error errorMessage
            }
        }
        stage("Remove custom resources") {
            new k8sResource(context.codebaseBranchCR, "${context.codebase}-${context.codebaseBranch}", this).remove()
        }
        stage("Remove database entries") {
            context.database.removeCodebaseBranch(context.projectName, context.codebase, context.codebaseBranch, params.RESOURCES_VERSION_2)
        }
        stage("Remove Jenkins jobs and view") {
            if (context.codebaseType != "autotests")
                new JenkinsItem("${context.codebase}/${context.codebaseBranch.toUpperCase()}-Build-${context.codebase}", this).remove()
            new JenkinsItem("${context.codebase}/${context.codebaseBranch.toUpperCase()}-Code-review-${context.codebase}", this).remove()
            new JenkinsView(context.codebase, context.codebaseBranch.toUpperCase(), this).remove()
        }
        stage("Remove codebase image stream") {
            if (context.codebaseType != "autotests")
                new k8sResource("codebaseimagestream", "${context.codebase}-${context.codebaseBranch}", this).remove()
        }
    }
}
