/*
 * Copyright (c) 2017, EPAM SYSTEMS INC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.epam.dlab.backendapi.resources.dto.azure;

import com.epam.dlab.backendapi.resources.dto.UserComputationalResource;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.hibernate.validator.constraints.NotBlank;

import java.util.Date;

@Data
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class AzureComputationalResource extends UserComputationalResource {
    @NotBlank
    @JsonProperty("dataengine_instance_count")
    private String dataEngineInstanceCount;

    @NotBlank
    @JsonProperty("azure_dataengine_slave_size")
    private String dataEngineSlaveSize;

    @NotBlank
    @JsonProperty("azure_dataengine_master_size")
    private String dataEngineMasterSize;

    @Builder
    public AzureComputationalResource(String computationalName, String computationalId, String imageName,
                                      String templateName, String status, Date uptime,
                                      String dataEngineInstanceCount, String dataEngineSlaveSize,
                                      String dataEngineMasterSize) {

        super(computationalName, computationalId, imageName, templateName, status, uptime);
        this.dataEngineInstanceCount = dataEngineInstanceCount;
        this.dataEngineSlaveSize = dataEngineSlaveSize;
        this.dataEngineMasterSize = dataEngineMasterSize;
    }
}
