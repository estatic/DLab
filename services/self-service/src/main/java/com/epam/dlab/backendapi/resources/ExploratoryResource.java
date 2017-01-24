/***************************************************************************

Copyright (c) 2016, EPAM SYSTEMS INC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

****************************************************************************/

package com.epam.dlab.backendapi.resources;

import com.epam.dlab.UserInstanceStatus;
import com.epam.dlab.auth.UserInfo;
import com.epam.dlab.backendapi.core.UserInstanceDTO;
import com.epam.dlab.backendapi.dao.InfrastructureProvisionDAO;
import com.epam.dlab.backendapi.dao.SettingsDAO;
import com.epam.dlab.constants.ServiceConsts;
import com.epam.dlab.backendapi.resources.dto.ExploratoryActionFormDTO;
import com.epam.dlab.backendapi.resources.dto.ExploratoryCreateFormDTO;
import com.epam.dlab.dto.StatusBaseDTO;
import com.epam.dlab.dto.exploratory.ExploratoryActionDTO;
import com.epam.dlab.dto.exploratory.ExploratoryCreateDTO;
import com.epam.dlab.dto.exploratory.ExploratoryStatusDTO;
import com.epam.dlab.dto.exploratory.ExploratoryStopDTO;
import com.epam.dlab.exceptions.DlabException;
import com.epam.dlab.rest.client.RESTService;
import com.epam.dlab.rest.contracts.ApiCallbacks;
import com.epam.dlab.rest.contracts.ExploratoryAPI;
import com.epam.dlab.utils.UsernameUtils;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import io.dropwizard.auth.Auth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.util.Optional;

import static com.epam.dlab.UserInstanceStatus.*;

/** Provides the REST API for the exploratory.
 */
@Path("/infrastructure_provision/exploratory_environment")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ExploratoryResource implements ExploratoryAPI {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExploratoryResource.class);

    @Inject
    private SettingsDAO settingsDAO;
    @Inject
    private InfrastructureProvisionDAO infrastructureProvisionDAO;
    @Inject
    @Named(ServiceConsts.PROVISIONING_SERVICE_NAME)
    private RESTService provisioningService;

    /** Creates the exploratory environment for user.
     * @param userInfo user info.
     * @param formDTO description for the exploratory environment.
     * @return {@link Response.Status#OK} request for provisioning service has been accepted.<br>
     * {@link Response.Status#FOUND} request for provisioning service has been duplicated.
     * @exception DlabException
     */
    @PUT
    public Response create(@Auth UserInfo userInfo, @Valid @NotNull ExploratoryCreateFormDTO formDTO) throws DlabException {
        LOGGER.debug("Creating exploratory environment {} with name {} for user {}",
                formDTO.getImage(), formDTO.getName(), userInfo.getName());
        boolean isAdded = false;
        try {
        	isAdded = infrastructureProvisionDAO.insertExploratory(new UserInstanceDTO()
        			.withUser(userInfo.getName())
        			.withExploratoryName(formDTO.getName())
        			.withStatus(CREATING.toString())
        			.withImageName(formDTO.getImage())
        			.withImageVersion(formDTO.getVersion())
        			.withTemplateName(formDTO.getTemplateName())
                    .withShape(formDTO.getShape()));
        	if (isAdded) {
                ExploratoryCreateDTO dto = new ExploratoryCreateDTO()
                        .withServiceBaseName(settingsDAO.getServiceBaseName())
                        .withExploratoryName(formDTO.getName())
                        .withNotebookUserName(UsernameUtils.removeDomain(userInfo.getName()))
                        .withIamUserName(userInfo.getName())
                        .withNotebookImage(formDTO.getImage())
                        .withApplicationName(formDTO.getApplicationName())
                        .withNotebookInstanceType(formDTO.getShape())
                        .withAwsRegion(settingsDAO.getAwsRegion())
                        .withAwsSecurityGroupIds(settingsDAO.getAwsSecurityGroups())
                        .withConfOsUser(settingsDAO.getConfOsUser())
                        .withConfOsFamily(settingsDAO.getConfOsFamily());
                LOGGER.debug("Created exploratory environment {} for user {}", formDTO.getName(), userInfo.getName());
                return Response
                        .ok(provisioningService.post(EXPLORATORY_CREATE, userInfo.getAccessToken(), dto, String.class))
                        .build();
            } else {
                LOGGER.debug("Used existing exploratory environment {} for user {}", formDTO.getName(), userInfo.getName());
                return Response.status(Response.Status.FOUND).build();
            }
        } catch (Throwable t) {
        	LOGGER.warn("Could not update the status of exploratory environment {} with name {} for user {}: {}",
                    formDTO.getImage(), formDTO.getName(), userInfo.getName(), t.getLocalizedMessage(), t);
        	if (isAdded) {
        		updateExploratoryStatusSilent(userInfo.getName(), formDTO.getName(), FAILED);
        	}
            throw new DlabException("Could not create exploratory environment " + formDTO.getName() + " for user " + userInfo.getName(), t);
        }
    }

    /** Changes the status of exploratory environment.
     * @param dto description of status.
     * @return 200 OK - if request success.
     * @exception DlabException
     */
    @POST
    @Path(ApiCallbacks.STATUS_URI)
    public Response status(ExploratoryStatusDTO dto) throws DlabException {
        LOGGER.debug("Updating status for exploratory environment {} for user {} to {}",
        		dto.getExploratoryName(), dto.getUser(), dto.getStatus());
        UserInstanceStatus currentStatus;
        
        try {
        	currentStatus = infrastructureProvisionDAO.fetchExploratoryStatus(dto.getUser(), dto.getExploratoryName());
        } catch (DlabException e) {
        	LOGGER.warn("Could not get current status for exploratory environment {} for user {}: {}",
        			dto.getExploratoryName(), dto.getUser(), e.getLocalizedMessage(), e);
            throw new DlabException("Could not get current status for exploratory environment " + dto.getExploratoryName() +
            		" for user " + dto.getUser(), e);
        }
        LOGGER.debug("Current status for exploratory environment {} for user {} is {}",
        		dto.getExploratoryName(), dto.getUser(), currentStatus);

        try {
            infrastructureProvisionDAO.updateExploratoryFields(dto);
            if (currentStatus == TERMINATING) {
            	updateComputationalStatuses(dto.getUser(), dto.getExploratoryName(), UserInstanceStatus.of(dto.getStatus()));
            } else if (currentStatus == STOPPING) {
            	updateComputationalStatuses(dto.getUser(), dto.getExploratoryName(), TERMINATED);
            }
        } catch (DlabException e) {
        	LOGGER.warn("Could not update status for exploratory environment {} for user {} to {}: {}",
        			dto.getExploratoryName(), dto.getUser(), dto.getStatus(), e.getLocalizedMessage(), e);
        	throw new DlabException("Could not update status for exploratory environment " + dto.getExploratoryName() +
        			" for user " + dto.getUser() + " to " + dto.getStatus(), e);
        }

    	return Response.ok().build();
    }

    /** Starts exploratory environment for user.
     * @param userInfo user info.
     * @param formDTO description of exploratory action.
     * @return Invocation response as JSON string.
     * @throws DlabException
     */
    @POST
    public String start(@Auth UserInfo userInfo, @Valid @NotNull ExploratoryActionFormDTO formDTO) throws DlabException {
        LOGGER.debug("Starting exploratory environment {} for user {}", formDTO.getNotebookInstanceName(), userInfo.getName());
        try {
        	return action(userInfo, formDTO.getNotebookInstanceName(), EXPLORATORY_START, STARTING);
        } catch (DlabException e) {
        	LOGGER.warn("Could not start exploratory environment {} for user {}: {}",
        			formDTO.getNotebookInstanceName(), userInfo.getName(), e.getLocalizedMessage(), e);
        	throw new DlabException("Could not start exploratory environment " + formDTO.getNotebookInstanceName() +
        			" for user " + userInfo.getName(), e);
        }
    }

    /** Stops exploratory environment for user.
     * @param userInfo user info.
     * @param name name of exploratory environment.
     * @return Invocation response as JSON string.
     * @throws DlabException
     */
    @DELETE
    @Path("/{name}/stop")
    public String stop(@Auth UserInfo userInfo, @PathParam("name") String name) throws DlabException {
        System.out.println("stopping " + name);
        LOGGER.debug("Stopping exploratory environment {} for user {}", name, userInfo.getName());

        try {
        	updateExploratoryStatus(userInfo.getName(), name, STOPPING);
        	updateComputationalStatuses(userInfo.getName(), name, TERMINATING);
        } catch (DlabException e) {
        	LOGGER.warn("Could not update status for exploratory environment {} for user {}: {}",
        			name, userInfo.getName(), e.getLocalizedMessage(), e);
            throw new DlabException("Could not update status for exploratory environment " + name +
            		" for user " + userInfo.getName() + ": " + e.getLocalizedMessage(), e);
        }
        
        try {
            Optional<UserInstanceDTO> opt =
                    infrastructureProvisionDAO.fetchExploratoryFields(userInfo.getName(), name);
            if(!opt.isPresent()) {
                throw new DlabException(String.format("Exploratory instance with name {} not found.", name));
            }

            UserInstanceDTO userInstance = opt.get();
            ExploratoryStopDTO dto = new ExploratoryStopDTO()
                    .withServiceBaseName(settingsDAO.getServiceBaseName())
                    .withNotebookImage(userInstance.getImageName())
                    .withExploratoryName(name)
                    .withNotebookUserName(UsernameUtils.removeDomain(userInfo.getName()))
                    .withIamUserName(userInfo.getName())
                    .withNotebookInstanceName(userInstance.getExploratoryId())
                    .withConfKeyDir(settingsDAO.getConfKeyDir())
                    .withConfOsUser(settingsDAO.getConfOsUser())
                    .withAwsRegion(settingsDAO.getAwsRegion());
            return provisioningService.post(EXPLORATORY_STOP, userInfo.getAccessToken(), dto, String.class);
        } catch (Throwable t) {
        	LOGGER.warn("Could not stop exploratory environment {} for user {}: {}",
                    name, userInfo.getName(), t.getLocalizedMessage(), t);
        	updateExploratoryStatusSilent(userInfo.getName(), name, FAILED);
            throw new DlabException("Could not stop exploratory environment " + name + " for user " + userInfo.getName(), t);
        }
    }

    /** Terminates exploratory environment for user.
     * @param userInfo user info.
     * @param name name of exploratory environment.
     * @return Invocation response as JSON string.
     * @throws DlabException
     */
    @DELETE
    @Path("/{name}/terminate")
    public String terminate(@Auth UserInfo userInfo, @PathParam("name") String name) throws DlabException {
        LOGGER.debug("Terminating exploratory environment {} for user {}", name, userInfo.getName());
        UserInstanceStatus status = TERMINATING;
        try {
            updateExploratoryStatus(userInfo.getName(), name, status);
            updateComputationalStatuses(userInfo.getName(), name, status);
        } catch (DlabException e) {
        	LOGGER.warn("Could not update status for exploratory environment {} for user {}: {}",
        			name, userInfo.getName(), e.getLocalizedMessage(), e);
            throw new DlabException("Could not update status for exploratory environment " + name +
            		" for user " + userInfo.getName() + ": " + e.getLocalizedMessage(), e);
        }
        
        try {
        	return action(userInfo, name, EXPLORATORY_TERMINATE, status);
        } catch (DlabException e) {
        	LOGGER.warn("Could not terminate exploratory environment {} for user {}: {}",
                    name, userInfo.getName(), e.getLocalizedMessage(), e);
           	throw new DlabException("Could not terminate exploratory environment " + name + " for user " + userInfo.getName(), e);
        }
    }

    /** Sends the post request to the provisioning service and update the status of exploratory environment.
     * @param userInfo user info.
     * @param exploratoryName name of exploratory environment.
     * @param action action for exploratory environment.
     * @param status status for exploratory environment.
     * @return Invocation request as JSON string.
     * @throws DlabException
     */
    private String action(UserInfo userInfo, String exploratoryName, String action, UserInstanceStatus status) throws DlabException {
        try {
            updateExploratoryStatus(userInfo.getName(), exploratoryName, status);
            Optional<UserInstanceDTO> opt =
                    infrastructureProvisionDAO.fetchExploratoryFields(userInfo.getName(), exploratoryName);
            if(!opt.isPresent())
                throw new DlabException(String.format("Exploratory instance with name {} not found.", exploratoryName));

            UserInstanceDTO userInstance = opt.get();
            ExploratoryActionDTO<?> dto = new ExploratoryActionDTO<>()
                    .withServiceBaseName(settingsDAO.getServiceBaseName())
                    .withNotebookImage(userInstance.getImageName())
                    .withExploratoryName(exploratoryName)
                    .withNotebookUserName(UsernameUtils.removeDomain(userInfo.getName()))
                    .withIamUserName(userInfo.getName())
                    .withNotebookInstanceName(userInstance.getExploratoryId())
                    .withAwsRegion(settingsDAO.getAwsRegion())
                    .withConfOsUser(settingsDAO.getConfOsUser())
                    .withConfOsFamily(settingsDAO.getConfOsFamily());
            return provisioningService.post(action, userInfo.getAccessToken(), dto, String.class);
        } catch (Throwable t) {
        	updateExploratoryStatusSilent(userInfo.getName(), exploratoryName, FAILED);
            throw new DlabException("Could not " + action + " exploratory environment " + exploratoryName, t);
        }
    }

    /** Instantiates and returns the descriptor of exploratory environment status.
     * @param user user name
     * @param exploratoryName name of exploratory environment.
     * @param status status for exploratory environment.
     */
    private StatusBaseDTO<?> createStatusDTO(String user, String exploratoryName, UserInstanceStatus status) {
        return new ExploratoryStatusDTO()
                .withUser(user)
                .withExploratoryName(exploratoryName)
                .withStatus(status);
    }

    /** Updates the computational status of exploratory environment.
     * @param user user name
     * @param exploratoryName name of exploratory environment.
     * @param status status for exploratory environment.
     * @throws DlabException
     */
    private void updateComputationalStatuses(String user, String exploratoryName, UserInstanceStatus status) throws DlabException {
        LOGGER.debug("updating status for all computational resources of {} for user {}: {}", exploratoryName, user, status);
        StatusBaseDTO<?> exploratoryStatus = createStatusDTO(user, exploratoryName, status);
        infrastructureProvisionDAO.updateComputationalStatusesForExploratory(exploratoryStatus);
    }

    /** Updates the status of exploratory environment.
     * @param user user name
     * @param exploratoryName name of exploratory environment.
     * @param status status for exploratory environment.
     * @throws DlabException
     */
    private void updateExploratoryStatus(String user, String exploratoryName, UserInstanceStatus status) throws DlabException {
        StatusBaseDTO<?> exploratoryStatus = createStatusDTO(user, exploratoryName, status);
        infrastructureProvisionDAO.updateExploratoryStatus(exploratoryStatus);
    }

    /** Updates the status of exploratory environment without exceptions. If exception occurred then logging it.
     * @param user user name
     * @param exploratoryName name of exploratory environment.
     * @param status status for exploratory environment.
     */
    private void updateExploratoryStatusSilent(String user, String exploratoryName, UserInstanceStatus status) {
    	try {
       		updateExploratoryStatus(user, exploratoryName, status);
       	} catch (DlabException e) {
            LOGGER.warn("Could not update the status of exploratory environment {} for user {} to {}: {}",
            		exploratoryName, user, status, e.getLocalizedMessage(), e);
       	}
    }
}
