package com.dotcms.rest;

import com.dotcms.repackage.com.fasterxml.jackson.databind.ObjectMapper;
import com.dotcms.repackage.com.google.common.annotations.VisibleForTesting;
import com.dotcms.repackage.javax.ws.rs.Consumes;
import com.dotcms.repackage.javax.ws.rs.DELETE;
import com.dotcms.repackage.javax.ws.rs.GET;
import com.dotcms.repackage.javax.ws.rs.POST;
import com.dotcms.repackage.javax.ws.rs.PUT;
import com.dotcms.repackage.javax.ws.rs.Path;
import com.dotcms.repackage.javax.ws.rs.PathParam;
import com.dotcms.repackage.javax.ws.rs.Produces;
import com.dotcms.repackage.javax.ws.rs.core.Context;
import com.dotcms.repackage.javax.ws.rs.core.MediaType;
import com.dotcms.repackage.javax.ws.rs.core.Response;
import com.dotcms.repackage.org.apache.commons.httpclient.HttpStatus;
import com.dotcms.repackage.org.codehaus.jettison.json.JSONException;
import com.dotcms.repackage.org.codehaus.jettison.json.JSONObject;
import com.dotcms.rest.api.v1.ruleengine.RestRule;
import com.dotcms.rest.config.AuthenticationProvider;
import com.dotcms.rest.exception.BadRequestException;
import com.dotcms.rest.exception.ForbiddenException;
import com.dotcms.rest.exception.InternalServerException;
import com.dotcms.rest.exception.NotFoundException;
import com.dotmarketing.beans.Host;
import com.dotmarketing.business.ApiProvider;
import com.dotmarketing.exception.DotDataException;
import com.dotmarketing.exception.DotSecurityException;
import com.dotmarketing.portlets.contentlet.business.HostAPI;
import com.dotmarketing.portlets.rules.actionlet.RuleActionlet;
import com.dotmarketing.portlets.rules.business.RulesAPI;
import com.dotmarketing.portlets.rules.conditionlet.Comparison;
import com.dotmarketing.portlets.rules.conditionlet.Conditionlet;
import com.dotmarketing.portlets.rules.model.*;
import com.dotmarketing.util.Logger;
import com.dotmarketing.util.UtilMethods;
import com.liferay.portal.model.User;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;

import static com.dotcms.rest.validation.Preconditions.checkNotEmpty;

@Path("/rules-engine")
public class RulesResource extends WebResource {

    private final RulesAPI rulesAPI;
    private final AuthenticationProvider authProxy;
    private HostAPI hostAPI;
    private ObjectMapper restRuleMapper = new ObjectMapper();

    public RulesResource() {
        this(new ApiProvider());
    }

    private RulesResource(ApiProvider apiProvider) {
        this(apiProvider, new AuthenticationProvider(apiProvider));
    }

    @VisibleForTesting
    protected RulesResource(ApiProvider apiProvider, AuthenticationProvider authProxy) {
        this.rulesAPI = apiProvider.rulesAPI();
        this.hostAPI = apiProvider.hostAPI();
        this.authProxy = authProxy;
    }


    @VisibleForTesting
    User getUser(@Context HttpServletRequest request) {
        return authProxy.authenticate(request);
    }

    @VisibleForTesting
    Host getHost(String siteId, User user){
        try {
            Host host = hostAPI.find(siteId, user, false);
            if(host == null) {
                throw new NotFoundException("Site not found: '%s'", siteId);
            }
            return host;
        } catch (DotDataException e) {
            // @todo ggranum: These messages potentially expose internal details to consumers, via response headers. See Note 1 in HttpStatusCodeException.
            throw new BadRequestException(e, e.getMessage());
        } catch (DotSecurityException e) {
            throw new ForbiddenException(e, e.getMessage());
        }
    }

    /**
     * <p>Returns a JSON representation of the rules defined in the given Host or Folder
     * <br>Each Rule node contains all fields in  .
     * <p>
     * Usage: /rules/{hostOrFolderIdentifier}
     */
    @GET
    @Path("/sites/{id}/rules")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRules(@Context HttpServletRequest request, @PathParam("id") String siteId) throws JSONException {
        siteId = checkNotEmpty(siteId, BadRequestException.class, "Site Id is required.");
        User user = getUser(request);
        Host host = getHost(siteId, user);
        JSONObject rulesJSON = getRulesInternal(user, host);
        return Response.ok(rulesJSON.toString(), MediaType.APPLICATION_JSON).build();
    }

    private JSONObject getRulesInternal(User user, Host host) {
        try {
            JSONObject rulesJSON = new JSONObject();
            List<Rule> rules = rulesAPI.getRulesByHost(host.getIdentifier(), user, false);
            for (Rule rule : rules) {
                rulesJSON.put(rule.getId(), getRuleJSON(rule, user));
            }
            return rulesJSON;
        } catch (DotDataException e) {
            throw new BadRequestException(e, e.getMessage());
        } catch (DotSecurityException e) {
            throw new ForbiddenException(e, e.getMessage());
        } catch (JSONException e) {
            throw new InternalServerException(e, e.getMessage());
        }
    }

    /**
     * <p>Returns a JSON representation of the Rule with the given ruleId
     * <p/>
     * Usage: GET api/rules-engine/sites/{siteId}/rules/{ruleId}
     *
     */
    @GET
    @Path("/sites/{siteId}/rules/{ruleId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRule(@Context HttpServletRequest request, @PathParam("siteId") String siteId, @PathParam("ruleId") String ruleId) {
        siteId = checkNotEmpty(siteId, BadRequestException.class, "Site Id is required.");
        ruleId = checkNotEmpty(ruleId, BadRequestException.class, "Rule Id is required.");
        User user = getUser(request);
        JSONObject ruleJSON = getRuleInternal(ruleId, user);

        return Response.ok(ruleJSON.toString(), MediaType.APPLICATION_JSON).build();
    }

    private JSONObject getRuleInternal(String ruleId, User user) {
        try {
            Rule rule = rulesAPI.getRuleById(ruleId, user, false);
            if(rule == null){
                throw new NotFoundException("Rule not found for id '%s'", ruleId);
            }
            return getRuleJSON(rule, user);
        } catch (DotDataException e) {
            throw new BadRequestException(e, e.getMessage());
        } catch (DotSecurityException e) {
            throw new ForbiddenException(e, e.getMessage());
        } catch (JSONException e) {
            throw new InternalServerException(e, e.getMessage());
        }
    }

    @GET
    @Path("site/{siteId}/rules/{ruleId}/conditiongroups")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConditionGroups(@Context HttpServletRequest request, @PathParam("siteId") String siteId, @PathParam("ruleId") String ruleId) throws JSONException {

      User user = getUser(request);

        try {

          Host host = getHost(siteId, user);

          if(host==null) {

                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Site not found").build();
            }

            JSONObject groupsJSON = new JSONObject();

            Rule rule = rulesAPI.getRuleById(ruleId, user, false);

            if (!UtilMethods.isSet(rule) || !UtilMethods.isSet(rule.getId())) {
                Logger.info(this, "Rule not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Rule not found").build();
            }

            List<ConditionGroup> conditionGroups = rulesAPI.getConditionGroupsByRule(ruleId, user, false);

            for (ConditionGroup conditionGroup : conditionGroups) {

                JSONObject groupJSON = new com.dotmarketing.util.json.JSONObject(conditionGroup, new String[]{"operator", "priority"});

                List<Condition> conditions = rulesAPI.getConditionsByConditionGroup(conditionGroup.getId(), user, false);

                JSONObject conditionsJSON = new JSONObject();

                for (Condition condition : conditions) {
                    JSONObject conditionJSON = new com.dotmarketing.util.json.JSONObject(condition);

                    JSONObject valuesJSON = new JSONObject();
                    for(ConditionValue value:condition.getValues()) {
                        valuesJSON.put(value.getId(), new com.dotmarketing.util.json.JSONObject(value, new String[]{"value", "priority"}));
                    }

                    conditionJSON.put("values", valuesJSON);
                    conditionsJSON.put(condition.getId(), conditionJSON);
                }

                groupJSON.put("conditions", conditionsJSON);

                groupsJSON.put(conditionGroup.getId(), groupJSON);
            }

            return Response.ok(groupsJSON.toString(), MediaType.APPLICATION_JSON).build();

        } catch (DotDataException | DotSecurityException e) {
            Logger.error(this, "Error getting Conditions", e);
            return Response.status(HttpStatus.SC_BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    @GET
    @Path("/sites/{siteId}/rules/{ruleId}/conditiongroups/{groupId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConditionGroups(@Context HttpServletRequest request, @PathParam("siteId") String siteId, @PathParam("ruleId") String ruleId,
                                       @PathParam("groupId") String groupId) throws JSONException {
      User user = getUser(request);

        try {

          Host host = getHost(siteId, user);

          if (host == null) {

                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Site not found").build();
            }

            Rule rule = rulesAPI.getRuleById(ruleId, user, false);

            if (!UtilMethods.isSet(rule) || !UtilMethods.isSet(rule.getId())) {
                Logger.info(this, "Rule not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Rule not found").build();
            }

            ConditionGroup group = rulesAPI.getConditionGroupById(groupId, user, false);

            if (group == null) {
                Logger.info(this, "Condition Group not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Condition Group not found").build();
            }


            JSONObject groupJSON = new com.dotmarketing.util.json.JSONObject(group, new String[]{"operator", "priority"});

            List<Condition> conditions = rulesAPI.getConditionsByConditionGroup(group.getId(), user, false);

            JSONObject conditionsJSON = new JSONObject();

            for (Condition condition : conditions) {
                JSONObject conditionJSON = new com.dotmarketing.util.json.JSONObject(condition);

                JSONObject valuesJSON = new JSONObject();
                for (ConditionValue value : condition.getValues()) {
                    valuesJSON.put(value.getId(), new com.dotmarketing.util.json.JSONObject(value, new String[]{"value", "priority"}));
                }

                conditionJSON.put("values", valuesJSON);
                conditionsJSON.put(condition.getId(), conditionJSON);
            }

            groupJSON.put("conditions", conditionsJSON);


            return Response.ok(groupJSON.toString(), MediaType.APPLICATION_JSON).build();

        } catch (DotDataException | DotSecurityException e) {
            Logger.error(this, "Error getting Conditions", e);
            return Response.status(HttpStatus.SC_BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * <p>Returns a JSON with the Condition Groups and its Conditions for the rule with the given ruleId.
     * <br>Each Rule node contains all fields in  .

     * Usage: /conditions/
     *
     * @throws com.dotmarketing.util.json.JSONException
     */

    @GET
    @Path("/sites/{siteId}/rules/{ruleId}/conditiongroups/{groupId}/conditions")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConditions(@Context HttpServletRequest request, @PathParam("siteId") String siteId, @PathParam("ruleId") String ruleId,
                                  @PathParam("groupId") String groupId) throws JSONException {
      User user = getUser(request);

        try {

          Host host = getHost(siteId, user);

          if(host==null) {

                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Site not found").build();
            }

            Rule rule = rulesAPI.getRuleById(ruleId, user, false);

            if (!UtilMethods.isSet(rule) || !UtilMethods.isSet(rule.getId())) {
                Logger.info(this, "Rule not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Rule not found").build();
            }

            ConditionGroup group = rulesAPI.getConditionGroupById(groupId, user, false);

            if (group == null) {
                Logger.info(this, "Condition Group not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Condition Group not found").build();
            }


            List<Condition> conditions = rulesAPI.getConditionsByConditionGroup(group.getId(), user, false);

            JSONObject conditionsJSON = new JSONObject();

            for (Condition condition : conditions) {
                JSONObject conditionJSON = new com.dotmarketing.util.json.JSONObject(condition);

                JSONObject valuesJSON = new JSONObject();
                for (ConditionValue value : condition.getValues()) {
                    valuesJSON.put(value.getId(), new com.dotmarketing.util.json.JSONObject(value, new String[]{"value", "priority"}));
                }

                conditionJSON.put("values", valuesJSON);
                conditionsJSON.put(condition.getId(), conditionJSON);
            }


            return Response.ok(conditionsJSON.toString(), MediaType.APPLICATION_JSON).build();

        } catch (DotDataException | DotSecurityException e) {
            Logger.error(this, "Error getting Conditions", e);
            return Response.status(HttpStatus.SC_BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * <p>Returns a JSON with the Condition Groups and its Conditions for the rule with the given ruleId.
     * <br>Each Rule node contains all fields in  .
     * <p/>
     * <p>If a conditionId is provided, it will return the condition whose id matches the provided conditionId.
     * <p/>
     * Usage: /conditions/
     *
     * @throws com.dotmarketing.util.json.JSONException
     */

    @GET
    @Path("/sites/{siteId}/rules/{ruleId}/conditiongroups/{groupId}/conditions/{conditionId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getCondition(@Context HttpServletRequest request, @PathParam("siteId") String siteId, @PathParam("ruleId") String ruleId,
                                 @PathParam("groupId") String groupId, @PathParam("conditionId") String conditionId) throws JSONException {
      User user = getUser(request);

        try {

          Host host = getHost(siteId, user);

          if(host==null) {

                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Site not found").build();
            }

            Rule rule = rulesAPI.getRuleById(ruleId, user, false);

            if (rule==null) {
                Logger.info(this, "Rule not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Rule not found").build();
            }

            ConditionGroup group = rulesAPI.getConditionGroupById(groupId, user, false);

            if (group == null) {
                Logger.info(this, "Condition Group not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Condition Group not found").build();
            }

            Condition condition = rulesAPI.getConditionById(conditionId, user, false);
            JSONObject conditionObject = new com.dotmarketing.util.json.JSONObject(condition);
            return Response.ok(conditionObject.toString(), MediaType.APPLICATION_JSON).build();
        } catch (DotDataException | DotSecurityException e) {
            Logger.error(this, "Error getting Condition", e);
            return Response.status(HttpStatus.SC_BAD_REQUEST).entity(e.getMessage()).build();
        }

    }

    /**
     * <p>Returns a JSON with all the Conditionlet Objects defined.
     * <br>Each Conditionlet node contains only its name
     * <p/>
     * Usage: /conditionlets/
     *
     * @throws com.dotmarketing.util.json.JSONException
     */

    @GET
    @Path("/conditionlets")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConditionlets(@Context HttpServletRequest request) throws JSONException {
        InitDataObject initData = init(null, true, request, true);


        JSONObject jsonConditionlets = new JSONObject();

        try {

            List<Conditionlet> conditionlets = rulesAPI.findConditionlets();

            for (Conditionlet conditionlet : conditionlets) {
                JSONObject conditionletObject = new JSONObject();
                conditionletObject.put("name", conditionlet.getLocalizedName());

                Set<Comparison> comparisons = conditionlet.getComparisons();
                JSONObject jsonComparisons = new JSONObject();

                for (Comparison comparison : comparisons) {
                    JSONObject comparisonJSON = new JSONObject();
                    comparisonJSON.put("name", comparison.getLabel());
                    jsonComparisons.put(comparison.getId(), comparisonJSON);
                }

                conditionletObject.put("comparisons", jsonComparisons);

                jsonConditionlets.put(conditionlet.getClass().getSimpleName(), conditionletObject);
            }

            return Response.ok(jsonConditionlets.toString(), MediaType.APPLICATION_JSON).build();

        } catch (DotDataException | DotSecurityException e) {
            Logger.error(this, "Error getting Conditionlets", e);
            return Response.status(HttpStatus.SC_BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * <p>Returns a JSON with the Comparisons of a given contentlet.
     * <br>Each Comparisons node contains the id and label
     * <p/>
     * Usage: /comparisons/conditionlet/{id}
     *
     * @throws com.dotmarketing.util.json.JSONException
     */

    @GET
    @Path("/conditionlets/{id}/comparisons")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getComparisons(@Context HttpServletRequest request, @PathParam("id") String conditionletId) throws JSONException {
      User user = getUser(request);


        JSONObject jsonComparisons = new JSONObject();

        if (!UtilMethods.isSet(conditionletId)) {
            return Response.ok(jsonComparisons.toString(), MediaType.APPLICATION_JSON).build();
        }

        try {
            Conditionlet conditionlet = rulesAPI.findConditionlet(conditionletId);

            if (!UtilMethods.isSet(conditionlet)) {
                return Response.ok(jsonComparisons.toString(), MediaType.APPLICATION_JSON).build();
            }

            Set<Comparison> comparisons = conditionlet.getComparisons();

            for (Comparison comparison : comparisons) {
                JSONObject comparisonJSON = new JSONObject();
                comparisonJSON.put("name", comparison.getLabel());
                jsonComparisons.put(comparison.getId(), comparisonJSON);
            }

            return Response.ok(jsonComparisons.toString(), MediaType.APPLICATION_JSON).build();
        } catch (DotDataException | DotSecurityException e) {
            Logger.error(this, "Error getting Conditionlet Comparisons", e);
            return Response.status(HttpStatus.SC_BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * <p>Returns a JSON with the Comparisons of a given contentlet.
     * <br>Each Comparisons node contains the id and label
     * <p/>
     * Usage: /conditionletInputs/
     *
     * @throws com.dotmarketing.util.json.JSONException
     */

    @GET
    @Path("/conditionlets/{id}/comparisons/{comparison}/inputs")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConditionletInputs(@Context HttpServletRequest request, @PathParam("id") String conditionletId, @PathParam("comparison") String comparison) throws JSONException {
        InitDataObject initData = init(null, true, request, true);


        com.dotmarketing.util.json.JSONArray jsonInputs = new com.dotmarketing.util.json.JSONArray();

        if (!UtilMethods.isSet(conditionletId) || !UtilMethods.isSet(comparison)) {
            return Response.ok(jsonInputs.toString(), MediaType.APPLICATION_JSON).build();
        }

        try {
            Conditionlet conditionlet = rulesAPI.findConditionlet(conditionletId);

            if (!UtilMethods.isSet(conditionlet)) {
                return Response.ok(jsonInputs.toString(), MediaType.APPLICATION_JSON).build();
            }

            jsonInputs.addAll(conditionlet.getInputs(comparison));

            return Response.ok(jsonInputs.toString(), MediaType.APPLICATION_JSON).build();
        } catch (DotDataException | DotSecurityException e) {
            Logger.error(this, "Error getting Conditionlet Inputs", e);
            return Response.status(HttpStatus.SC_BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * <p>Returns a JSON with all the RuleActionlet Objects defined.
     * <p/>
     * Usage: /ruleactionlets/
     *
     * @throws com.dotmarketing.util.json.JSONException
     */

    @GET
    @Path("/ruleactionlets")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRuleActionlets(@Context HttpServletRequest request) throws JSONException {
        InitDataObject initData = init(null, true, request, true);


        JSONObject jsonActionlets = new JSONObject();

        try {

            List<RuleActionlet> actionlets = rulesAPI.findActionlets();

            for (RuleActionlet actionlet : actionlets) {
                JSONObject actionletObject = new JSONObject();
                actionletObject.put("name", actionlet.getLocalizedName());
                jsonActionlets.put(actionlet.getClass().getSimpleName(), actionletObject);
            }

            return Response.ok(jsonActionlets.toString(), MediaType.APPLICATION_JSON).build();

        } catch (DotDataException | DotSecurityException e) {
            Logger.error(this, "Error getting Rule Actionlets", e);
            return Response.status(HttpStatus.SC_BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * <p>Returns a JSON with the RuleActions defined for the Rule with the given ruleId.
     * <p/>
     *
     * @throws com.dotmarketing.util.json.JSONException
     */

    @GET
    @Path("sites/{siteId}/rules/{ruleId}/actions")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRuleActions(@Context HttpServletRequest request, @PathParam("siteId") String siteId, @PathParam("ruleId") String ruleId) throws JSONException {

      User user = getUser(request);

        JSONObject jsonActions = new JSONObject();

        try {

          Host host = getHost(siteId, user);

          if(host==null) {

                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Site not found").build();
            }

            Rule rule = rulesAPI.getRuleById(ruleId, user, false);

            if (rule==null) {
                Logger.info(this, "Rule not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Rule not found").build();
            }

            List<RuleAction> actions = rulesAPI.getRuleActionsByRule(rule.getId(), user, false);

            for (RuleAction action : actions) {
                JSONObject actionletObject = new JSONObject();
                actionletObject.put("name", action.getName());
                actionletObject.put("actionlet", action.getActionlet());
                jsonActions.put(action.getId(), actionletObject);
            }


            return Response.ok(jsonActions.toString(), MediaType.APPLICATION_JSON).build();

        } catch (DotDataException | DotSecurityException e) {
            Logger.error(this, "Error getting Rule Action", e);
            return Response.status(HttpStatus.SC_BAD_REQUEST).entity(e.getMessage()).build();
        }
    }


    /**
     * <p>Saves a new Rule
     * <br>
     * <p/>
     * Usage: /rules/
     *
     */

    @POST
    @Path("/sites/{id}/rules")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveRule(@Context HttpServletRequest request, @PathParam("id") String siteId, JSONObject ruleJSON) {
        siteId = checkNotEmpty(siteId, BadRequestException.class, "Site id is required.");
        User user = getUser(request);
        Host host = getHost(siteId, user);
        RestRule restRule = mapJsonToRestRule(host, ruleJSON);
        String ruleId = createRuleInternal(host, restRule, user);
        try {
            URI path = new URI(ruleId);
            return Response.ok().type(MediaType.APPLICATION_JSON).entity("{ \"id\": \"" + ruleId + "\" }").build();
        } catch (URISyntaxException e) {
            throw new InternalServerException(e, "Could not create valid URI to Rule id '%s'", ruleId);
        }
    }

    /**
     * <p>Updates a new Rule
     * <br>
     * <p/>
     * Usage: /rules/
     *
     * @throws com.dotmarketing.util.json.JSONException
     */

    @PUT
    @Path("/sites/{siteId}/rules/{ruleId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateRule(@Context HttpServletRequest request, @PathParam("siteId") String siteId, @PathParam("ruleId") String ruleId, JSONObject ruleJSON)  {
        siteId = checkNotEmpty(siteId, BadRequestException.class, "Site Id is required.");
        ruleId = checkNotEmpty(ruleId, BadRequestException.class, "Rule Id is required.");
        User user = getUser(request);
        Host host = getHost(siteId, user); // forces check that host exists. This should be handled by rulesAPI?

        RestRule restRule = mapJsonToRestRule(host, ruleJSON);

        updateRuleInternal(user, ruleId, restRule);

        return Response.ok(ruleJSON.toString(), MediaType.APPLICATION_JSON).build();
    }

    /**
     * <p>Saves a Condition Group
     * <br>
     * <p/>
     * Usage: /rules/
     *
     * @throws com.dotmarketing.util.json.JSONException
     */

    @POST
    @Path("/sites/{siteId}/rules/{ruleId}/conditiongroups")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveConditionGroup(@Context HttpServletRequest request,  @PathParam("siteId") String siteId, @PathParam("ruleId") String ruleId, JSONObject groupJSON) throws
            JSONException {
      User user = getUser(request);

        JSONObject resultsObject = new JSONObject();

        try {

          Host host = getHost(siteId, user);

          if(host==null) {

                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Site not found").build();
            }

            Rule rule = rulesAPI.getRuleById(ruleId, user, false);

            if (rule==null) {
                Logger.info(this, "Rule not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Rule not found").build();
            }

            ConditionGroup group = new ConditionGroup();
            group.setRuleId(ruleId);
            group.setOperator(Condition.Operator.valueOf(groupJSON.optString("operator", Condition.Operator.AND.name())));
            group.setPriority(groupJSON.optInt("priority", 0));

            rulesAPI.saveConditionGroup(group, user, false);
            resultsObject.put("id", group.getId());
            return Response.ok(resultsObject.toString(), MediaType.APPLICATION_JSON).build();

        } catch (DotDataException | DotSecurityException e) {
            Logger.error(this, "Error saving Condition Group", e);
            return Response.status(HttpStatus.SC_BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * <p>Updates a Condition Group
     * <br>
     * <p/>
     * Usage: /rules/
     *
     * @throws com.dotmarketing.util.json.JSONException
     */

    @PUT
    @Path("/sites/{siteId}/rules/{ruleId}/conditiongroups/{groupId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateConditionGroup(@Context HttpServletRequest request, @PathParam("siteId") String siteId, @PathParam("ruleId") String ruleId, @PathParam("groupId") String groupId,
                                         JSONObject groupJSON) throws JSONException {
      User user = getUser(request);

        try {

          Host host = getHost(siteId, user);

          if(host==null) {

                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Site not found").build();
            }

            Rule rule = rulesAPI.getRuleById(ruleId, user, false);

            if (rule==null) {
                Logger.info(this, "Rule not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Rule not found").build();
            }

            if(!UtilMethods.isSet(groupId)) {
                Logger.info(getClass(), "Unable to update Condition Group - 'id' not provided");
                throw new DotDataException("Unable to update Condition Group - 'id' not provided");
            }

            ConditionGroup group = rulesAPI.getConditionGroupById(groupId, user, false);

            if (group == null) {
                Logger.info(this, "Condition Group not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Condition Group not found").build();
            }

            group.setOperator(Condition.Operator.valueOf(groupJSON.optString("operator", Condition.Operator.AND.name())));
            group.setPriority(groupJSON.optInt("priority", 0));

            return Response.ok(groupJSON.toString(), MediaType.APPLICATION_JSON).build();


        } catch (DotDataException | DotSecurityException e) {
            Logger.error(this, "Error updating Condition Group", e);
            return Response.status(HttpStatus.SC_BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * <p>Saves a new Condition
     * <br>
     * <p/>
     * Usage: /rules/
     *
     * @throws com.dotmarketing.util.json.JSONException
     */

    @POST
    @Path("/sites/{siteId}/rules/{ruleId}/conditiongroups/{groupId}/conditions")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveCondition(@Context HttpServletRequest request,  @PathParam("siteId") String siteId,  @PathParam("ruleId") String ruleId,
                                  @PathParam("groupId") String groupId, JSONObject conditionJSON) throws DotDataException, DotSecurityException, JSONException {

      User user = getUser(request);

        JSONObject resultsObject = new JSONObject();

        try {

          Host host = getHost(siteId, user);

          if(host==null) {

                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Site not found").build();
            }

            Rule rule = rulesAPI.getRuleById(ruleId, user, false);

            if (rule==null) {
                Logger.info(this, "Rule not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Rule not found").build();
            }

            if(!UtilMethods.isSet(groupId)) {
                Logger.info(getClass(), "Unable to save Condition - 'groupId' not provided");
                throw new DotDataException("Unable to save Condition - 'groupId' not provided");
            }

            ConditionGroup group = rulesAPI.getConditionGroupById(groupId, user, false);

            if(group==null) {
                Logger.info(this, "Condition Group not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Condition Group not found").build();
            }

            Condition condition = new Condition();
            condition.setName(conditionJSON.getString("name"));
            condition.setRuleId(group.getRuleId());
            condition.setConditionletId(conditionJSON.getString("conditionlet"));
            condition.setConditionGroup(groupId);
            condition.setComparison(conditionJSON.getString("comparison"));
            condition.setOperator(Condition.Operator.valueOf(conditionJSON.optString("operator", Condition.Operator.AND.name())));
            condition.setPriority(conditionJSON.optInt("priority", 0));

            com.dotcms.repackage.org.codehaus.jettison.json.JSONArray valuesJSON = conditionJSON.optJSONArray("values");
            List<ConditionValue> values = new ArrayList<>();

            if(UtilMethods.isSet(valuesJSON)) {
                for(int i=0; i<valuesJSON.length(); i++) {
                    JSONObject valueJSON = valuesJSON.getJSONObject(i);
                    ConditionValue value = new ConditionValue();
                    value.setValue(valueJSON.getString("value"));
                    value.setPriority(valueJSON.optInt("priority", 0));
                    values.add(value);
                }
            }

            condition.setValues(values);

            rulesAPI.saveCondition(condition, user, false);

            resultsObject.put("id", condition.getId());

            JSONObject valuesObject = new JSONObject();

            for (ConditionValue value : values) {
                valuesObject.put(value.getId(), value.getValue());
            }

            resultsObject.put("values", valuesObject);
            return Response.ok(resultsObject.toString(), MediaType.APPLICATION_JSON).build();

        } catch (DotDataException | DotSecurityException e) {
            Logger.error(this, "Error saving Condition", e);
            return Response.status(HttpStatus.SC_BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * <p>Updates a Condition
     * <br>
     * <p/>
     * Usage: PUT /rules/conditiongroups/{groupId}/conditions
     *
     * @throws com.dotmarketing.util.json.JSONException
     */

    @PUT
    @Path("/sites/{siteId}/rules/{ruleId}/conditiongroups/{groupId}/conditions/{conditionId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateCondition(@Context HttpServletRequest request, @PathParam("siteId") String siteId, @PathParam("ruleId") String ruleId,
                                    @PathParam("groupId") String groupId, @PathParam("conditionId") String conditionId, JSONObject conditionJSON) throws DotDataException, DotSecurityException, JSONException {

      User user = getUser(request);

        try {

          Host host = getHost(siteId, user);

          if(host==null) {

                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Site not found").build();
            }

            Rule rule = rulesAPI.getRuleById(ruleId, user, false);

            if (rule==null) {
                Logger.info(this, "Rule not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Rule not found").build();
            }

            ConditionGroup group = rulesAPI.getConditionGroupById(groupId, user, false);

            if(group==null) {
                Logger.info(this, "Condition Group not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Condition Group not found").build();
            }

            if(!UtilMethods.isSet(conditionId)) {
                Logger.info(getClass(), "Unable to update Condition - 'id' not provided");
                throw new DotDataException("Unable to update Condition - 'id' not provided");
            }

            Condition condition = rulesAPI.getConditionById(conditionId, user, false);

            if (condition==null) {
                Logger.info(this, "Condition not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Condition not found").build();
            }

            condition.setName(conditionJSON.getString("name"));
            condition.setRuleId(conditionJSON.getString("ruleId"));
            condition.setConditionletId(conditionJSON.getString("conditionlet"));
            condition.setConditionGroup(conditionJSON.getString("conditionGroupId"));
            condition.setComparison(conditionJSON.getString("comparison"));
            condition.setOperator(Condition.Operator.valueOf(conditionJSON.optString("operator", Condition.Operator.AND.name())));
            condition.setPriority(conditionJSON.optInt("priority", 0));

            com.dotcms.repackage.org.codehaus.jettison.json.JSONArray valuesJSON = conditionJSON.getJSONArray("values");
            List<ConditionValue> values = new ArrayList<>();

            if(UtilMethods.isSet(values)) {
                for(int i=0; i<valuesJSON.length(); i++) {
                    JSONObject valueJSON = (JSONObject) valuesJSON.get(i);
                    ConditionValue value = rulesAPI.getConditionValueById(valueJSON.getString("id"), user, false);
                    value.setValue(valueJSON.getString("value"));
                    value.setPriority(valueJSON.optInt("priority", 0));
                    values.add(value);
                }
            }

            condition.setValues(values);

            rulesAPI.saveCondition(condition, user, false);

            JSONObject conditionObject = new com.dotmarketing.util.json.JSONObject(condition);
            return Response.ok(conditionObject.toString(), MediaType.APPLICATION_JSON).build();

        } catch (DotDataException | DotSecurityException e) {
            Logger.error(this, "Error updating Condition", e);
            return Response.status(HttpStatus.SC_BAD_REQUEST).entity(e.getMessage()).build();
        }
    }
    /**
     * <p>Saves a Rule Action
     * <br>
     * <p/>
     * Usage: /rules/
     *
     * @throws com.dotmarketing.util.json.JSONException
     */

    @POST
    @Path("/sites/{siteId}/rules/{ruleId}/actions/")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveRuleAction(@Context HttpServletRequest request,@PathParam("siteId") String siteId, @PathParam("ruleId") String ruleId, JSONObject actionJSON) throws
            JSONException {
      User user = getUser(request);

        JSONObject resultsObject = new JSONObject();

        try {

          Host host = getHost(siteId, user);

          if(host==null) {

                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Site not found").build();
            }

            Rule rule = rulesAPI.getRuleById(ruleId, user, false);

            if (rule==null) {
                Logger.info(this, "Rule not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Rule not found").build();
            }

            RuleAction action = new RuleAction();
            action.setRuleId(ruleId);

            RuleActionlet actionlet = rulesAPI.findActionlet(actionJSON.getString("actionlet"));

            if(actionlet==null) {
                Logger.info(this, "Rule Actionlet not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Rule Actionlet not found").build();
            }

            action.setName(actionlet.getName());
            action.setPriority(actionJSON.optInt("priority", 0));
            action.setActionlet(actionJSON.getString("actionlet"));


            com.dotcms.repackage.org.codehaus.jettison.json.JSONArray parametersJSON = actionJSON.optJSONArray("parameters");
            List<RuleActionParameter> parameters = new ArrayList<>();

            if(UtilMethods.isSet(parametersJSON)) {
                for(int i=0; i<parametersJSON.length(); i++) {
                    JSONObject parameterJSON = (JSONObject) parametersJSON.get(i);
                    RuleActionParameter parameter = new RuleActionParameter();
                    parameter.setKey(parameterJSON.getString("key"));
                    parameter.setValue(parameterJSON.getString("value"));
                    parameters.add(parameter);
                }
            }


            action.setParameters(parameters);

            rulesAPI.saveRuleAction(action, user, false);

            resultsObject.put("id", action.getId());
            return Response.ok(resultsObject.toString(), MediaType.APPLICATION_JSON).build();

        } catch (DotDataException | DotSecurityException e) {
            Logger.error(this, "Error saving Rule Action", e);
            return Response.status(HttpStatus.SC_BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * <p>Updates the Rule Action with the given id
     * <br>
     * <p/>
     * Usage: /rules/
     *
     * @throws com.dotmarketing.util.json.JSONException
     */

    @PUT
    @Path("/sites/{siteId}/rules/{ruleId}/actions/{actionId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateRuleAction(@Context HttpServletRequest request,@PathParam("siteId") String siteId, @PathParam("ruleId") String ruleId,  @PathParam("actionId") String actionId, JSONObject actionJSON) throws
            JSONException {
      User user = getUser(request);

        try {

          Host host = getHost(siteId, user);

          if(host==null) {

                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Site not found").build();
            }

            Rule rule = rulesAPI.getRuleById(ruleId, user, false);

            if (rule==null) {
                Logger.info(this, "Rule not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Rule not found").build();
            }

            RuleAction action = rulesAPI.getRuleActionById(actionId, user, false);

            if(action==null) {
                Logger.info(this, "Rule Action not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Rule Action not found").build();
            }


            action.setRuleId(actionJSON.getString("ruleId"));

            RuleActionlet actionlet = rulesAPI.findActionlet(actionJSON.getString("actionlet"));

            if(actionlet==null) {
                Logger.info(this, "Rule Actionlet not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Rule Actionlet not found").build();
            }

            action.setName(actionlet.getName());
            action.setPriority(actionJSON.optInt("priority", 0));
            action.setActionlet(actionJSON.getString("actionlet"));

            com.dotcms.repackage.org.codehaus.jettison.json.JSONArray parametersJSON = actionJSON.optJSONArray("parameters");
            List<RuleActionParameter> parameters = new ArrayList<>();

            if(UtilMethods.isSet(parametersJSON)) {
                for(int i=0; i<parametersJSON.length(); i++) {
                    JSONObject parameterJSON = (JSONObject) parametersJSON.get(i);
                    RuleActionParameter parameter = rulesAPI.getRuleActionParameterById(parameterJSON.getString("id"), user, false);
                    parameter.setKey(parameterJSON.getString("key"));
                    parameter.setValue(parameterJSON.getString("value"));
                    parameters.add(parameter);
                }
            }

            action.setParameters(parameters);

            rulesAPI.saveRuleAction(action, user, false);

            return Response.ok(actionJSON.toString(), MediaType.APPLICATION_JSON).build();

        } catch (DotDataException | DotSecurityException e) {
            Logger.error(this, "Error updating Rule Action", e);
            return Response.status(HttpStatus.SC_BAD_REQUEST).entity(e.getMessage()).build();
        }
    }


    /**
     * <p>Deletes a Rule
     * <br>
     * <p/>
     * Usage: DELETE api/rules-engine/rules/{ruleId}
     *
     * @throws com.dotmarketing.util.json.JSONException
     */

    @DELETE
    @Path("/sites/{siteId}/rules/{ruleId}")
    public Response deleteRule(@Context HttpServletRequest request, @PathParam("siteId") String siteId, @PathParam("ruleId") String ruleId) throws
            JSONException {
      User user = getUser(request);

        try {

          Host host = getHost(siteId, user);

          if(host==null) {

                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Site not found").build();
            }

            Rule rule = rulesAPI.getRuleById(ruleId, user, false);

            if (rule==null) {
                Logger.info(this, "Rule not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Rule not found").build();
            }

            rulesAPI.deleteRule(rule, user, false);

            return Response.status(HttpStatus.SC_NO_CONTENT).build();
        } catch (DotDataException | DotSecurityException e) {
            return Response.status(HttpStatus.SC_BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * <p>Deletes a Condition Group and all its child Conditions
     * <br>
     * <p/>
     * Usage: DELETE api/rules-engine/conditiongroups
     *
     * @throws com.dotmarketing.util.json.JSONException
     */

    @DELETE
    @Path("/sites/{siteId}/rules/{ruleId}/conditiongroups/{conditionGroupId}")
    public Response deleteConditionGroup(@Context HttpServletRequest request, @PathParam("siteId") String siteId,
             @PathParam("ruleId") String ruleId, @PathParam("conditionGroupId") String conditionGroupId) throws
            JSONException {
      User user = getUser(request);

        try {

          Host host = getHost(siteId, user);

          if(host==null) {

                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Site not found").build();
            }

            Rule rule = rulesAPI.getRuleById(ruleId, user, false);

            if (rule==null) {
                Logger.info(this, "Rule not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Rule not found").build();
            }

            ConditionGroup group = rulesAPI.getConditionGroupById(conditionGroupId, user, false);

            if(group==null) {
                Logger.info(this, "Condition Group not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Condition Group not found").build();
            }

            rulesAPI.deleteConditionGroup(group, user, false);

            return Response.status(HttpStatus.SC_NO_CONTENT).build();
        } catch (DotDataException | DotSecurityException e) {
            return Response.status(HttpStatus.SC_BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * <p>Deletes a Condition
     * <br>
     * <p/>
     * Usage: DELETE api/rules-engine/rules
     *
     * @throws com.dotmarketing.util.json.JSONException
     */

    @DELETE
    @Path("/sites/{siteId}/rules/{ruleId}/conditiongroups/{groupId}/conditions/{conditionId}")
    public Response deleteCondition(@Context HttpServletRequest request, @PathParam("siteId") String siteId, @PathParam("groupId") String groupId,
                                    @PathParam("ruleId") String ruleId, @PathParam("conditionId") String conditionId) throws
            JSONException {
      User user = getUser(request);

        try {
          Host host = getHost(siteId, user);

          if(host==null) {

                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Site not found").build();
            }

            Rule rule = rulesAPI.getRuleById(ruleId, user, false);

            if (rule==null) {
                Logger.info(this, "Rule not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Rule not found").build();
            }

            ConditionGroup group = rulesAPI.getConditionGroupById(groupId, user, false);

            if(group==null) {
                Logger.info(this, "Condition Group not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Condition Group not found").build();
            }

            Condition condition = rulesAPI.getConditionById(conditionId, user, false);

            if (condition==null) {
                Logger.info(this, "Condition not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Condition not found").build();
            }

            rulesAPI.deleteCondition(condition, user, false);

            return Response.status(HttpStatus.SC_NO_CONTENT).build();
        } catch (DotDataException | DotSecurityException e) {
            return Response.status(HttpStatus.SC_BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * <p>Deletes the RuleAction with the given ruleActionId
     * <br>
     * <p/>
     * Usage: DELETE api/rules-engine/rules/actions/{ruleActionId}
     *
     * @throws com.dotmarketing.util.json.JSONException
     */

    @DELETE
    @Path("/sites/{siteId}/rules/{ruleId}/actions/{actionId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response deleteRuleAction(@Context HttpServletRequest request, @PathParam("siteId") String siteId,
                                     @PathParam("ruleId") String ruleId, @PathParam("actionId") String actionId) throws
            JSONException {
      User user = getUser(request);

        try {
          Host host = getHost(siteId, user);

          if(host==null) {

                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Site not found").build();
            }

            Rule rule = rulesAPI.getRuleById(ruleId, user, false);

            if (rule==null) {
                Logger.info(this, "Rule not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Rule not found").build();
            }

            RuleAction action = rulesAPI.getRuleActionById(actionId, user, false);

            if(action==null) {
                Logger.info(this, "Rule Action not found");
                return Response.status(HttpStatus.SC_NOT_FOUND).entity("Rule Action not found").build();
            }

            rulesAPI.deleteRuleAction(action, user, false);

            return Response.status(HttpStatus.SC_NO_CONTENT).build();
        } catch (DotDataException | DotSecurityException e) {
            return Response.status(HttpStatus.SC_BAD_REQUEST).entity(e.getMessage()).build();
        }
    }


    private JSONObject getRuleJSON(Rule rule, User user) throws DotDataException, DotSecurityException, JSONException {
        JSONObject ruleJSON = new com.dotmarketing.util.json.JSONObject(rule);

        List<ConditionGroup> groups = rulesAPI.getConditionGroupsByRule(rule.getId(), user, false);
        JSONObject groupsJSON = new JSONObject();

        for (ConditionGroup group : groups) {
            groupsJSON.put(group.getId(), new com.dotmarketing.util.json.JSONObject(group, new String[]{"operator", "priority"}));
        }

        ruleJSON.put("conditionGroups", groupsJSON);

        List<RuleAction> actions = rulesAPI.getRuleActionsByRule(rule.getId(), user, false);
        JSONObject actionsJSON = new JSONObject();

        for (RuleAction action : actions) {
            groupsJSON.put(action.getId(), new com.dotmarketing.util.json.JSONObject(action, new String[]{"priority"}));
        }

        ruleJSON.put("actions", actionsJSON);
        return ruleJSON;
    }

    private String createRuleInternal(Host host, RestRule restRule, User user) {
        try {
            Rule rule = new Rule();
            applyRestRuleToRule(restRule, rule);
            rulesAPI.saveRule(rule, user, false);
            return rule.getId();
        } catch (DotDataException e) {
            throw new BadRequestException(e, e.getMessage());
        } catch (DotSecurityException e) {
            throw new ForbiddenException(e, e.getMessage());
        }
    }



    private String updateRuleInternal(User user, String ruleId, RestRule restRule) {
        try {
            Rule rule = rulesAPI.getRuleById(ruleId, user, false);
            if(rule == null) {
                throw new NotFoundException("Rule with id '%s' not found: ", ruleId);
            }
            applyRestRuleToRule(restRule, rule);
            rulesAPI.saveRule(rule, user, false);
            return rule.getId();
        } catch (DotDataException e) {
            throw new BadRequestException(e, e.getMessage());
        } catch (DotSecurityException e) {
            throw new ForbiddenException(e, e.getMessage());
        }
    }


    private void applyRestRuleToRule(RestRule restRule, Rule rule) {
        rule.setHost(restRule.site);
        rule.setName(restRule.name);
        rule.setFireOn(Rule.FireOn.valueOf(restRule.fireOn));
        rule.setPriority(restRule.priority);
        rule.setShortCircuit(restRule.shortCircuit);
        rule.setEnabled(restRule.enabled);
    }

    @VisibleForTesting
    RestRule mapJsonToRestRule(Host host, JSONObject json) {
        try {
            RestRule restRule = restRuleMapper.readValue(json.toString(), RestRule.class);
            return restRule;
        } catch (IOException e) {
            throw new BadRequestException(e, e.getMessage());
        }
    }

}
