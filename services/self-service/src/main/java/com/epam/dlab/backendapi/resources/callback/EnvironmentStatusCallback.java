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

package com.epam.dlab.backendapi.resources.callback;

import com.epam.dlab.UserInstanceStatus;
import com.epam.dlab.backendapi.dao.EnvStatusDAO;
import com.epam.dlab.backendapi.domain.RequestId;
import com.epam.dlab.dto.status.EnvStatusDTO;
import com.epam.dlab.exceptions.DlabException;
import com.epam.dlab.rest.contracts.ApiCallbacks;
import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("/infrastructure")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Slf4j
public class EnvironmentStatusCallback {

    @Inject
    private EnvStatusDAO envDAO;

    /**
     * Updates the status of the resources for user.
     *
     * @param dto DTO info about the statuses of resources.
     * @return Always return code 200 (OK).
     */
    @POST
    @Path(ApiCallbacks.STATUS_URI)
    public Response status(EnvStatusDTO dto) {
        log.trace("Updating the status of resources for user {}: {}", dto.getUser(), dto);
        RequestId.checkAndRemove(dto.getRequestId());
        try {
            if (UserInstanceStatus.FAILED == UserInstanceStatus.of(dto.getStatus())) {
                log.warn("Request for the status of resources for user {} fails: {}", dto.getUser(), dto.getErrorMessage());
            } else {
                envDAO.updateEnvStatus(dto.getUser(), dto.getResourceList());
            }
        } catch (DlabException e) {
            log.warn("Could not update status of resources for user {}: {}", dto.getUser(), e.getLocalizedMessage(), e);
        }
        // Always necessary send OK for status request
        return Response.ok().build();
    }
}