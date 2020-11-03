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

package com.epam.edp.removepipes.k8s

class k8sResource {

    Script script
    String type
    String name

    k8sResource(String type, String name, Script script) {
        this.type = type
        this.name = name
        this.script = script
    }

    void remove() {
        try {
            script.openshift.withCluster() {
                script.openshift.withProject() {
                    script.openshift.raw("delete", type, name, "--ignore-not-found=true")
                    script.println("k8s resource \"${type}\" with name \"${name}\" has been removed")
                }
            }
        } catch (Exception ex) {
            script.error "Failed in removing \"${type}\" resource with name \"${name}\". " +
                    "Exception message:\n${ex}"
        }
    }
}