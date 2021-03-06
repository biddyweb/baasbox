/*
 * Copyright (c) 2014.
 *
 * BaasBox - info@baasbox.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.baasbox.controllers;

import com.baasbox.controllers.actions.filters.CheckAdminRoleFilter;
import com.baasbox.controllers.actions.filters.ConnectToDBFilter;
import com.baasbox.controllers.actions.filters.ExtractQueryParameters;
import com.baasbox.controllers.actions.filters.UserCredentialWrapFilter;
import com.baasbox.dao.ScriptsDao;
import com.baasbox.dao.exception.ScriptAlreadyExistsException;
import com.baasbox.dao.exception.ScriptException;
import com.baasbox.dao.exception.SqlInjectionException;
import com.baasbox.service.scripting.ScriptingService;
import com.baasbox.service.scripting.base.ScriptEvalException;
import com.baasbox.service.scripting.base.ScriptLanguage;
import com.baasbox.service.scripting.base.ScriptStatus;
import com.baasbox.util.IQueryParametersKeys;
import com.baasbox.util.JSONFormats;
import com.baasbox.util.QueryParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.baasbox.service.logging.BaasBoxLogger;

import play.mvc.*;
import play.mvc.Http.Context;

import java.io.IOException;
import java.util.List;

/**
 * Created by Andrea Tortorella on 10/06/14.
 */
@With({UserCredentialWrapFilter.class,ConnectToDBFilter.class, CheckAdminRoleFilter.class,ExtractQueryParameters.class})
public class ScriptsAdmin extends Controller{

    private static Result _activate(String name,boolean activate){
        if (BaasBoxLogger.isTraceEnabled()) BaasBoxLogger.trace("Start Method");
        Boolean s =ScriptingService.activate(name, activate);
        Result res = null;
        if (s ==null){
            res = notFound("Script: "+name+" does not exists");
        } else if (s){
            res = ok("Script: "+name+(activate? " is now active":" is no longer active"));
        } else {
            res = ok("Script already "+name+(activate? " active":" deactivated"));
        }
        if (BaasBoxLogger.isTraceEnabled()) BaasBoxLogger.trace("End Method");
        return res;
    }
    
    public static Result activate(String name){
    	return _activate(name,true);
    }
    
    public static Result deactivate(String name){
    	return _activate(name,false);
    }
    

    @BodyParser.Of(BodyParser.Json.class)
    public static Result update(String name){
        if (BaasBoxLogger.isTraceEnabled()) BaasBoxLogger.trace("Start Method");
        Http.Request req = request();
        Result result;
        JsonNode body = req.body().asJson();

        try {

            ScriptStatus update = ScriptingService.update(name, body);
            if (update.ok){
                result = ok(update.message);
            } else {
                result = badRequest(update.message);
            }
        } catch (ScriptEvalException e) {
            BaasBoxLogger.error("Evaluation exception: "+e.getMessage(),e);
            result = badRequest(e.getMessage());
        } catch (ScriptException e){
            BaasBoxLogger.error("Script exception: ",e);
            result = notFound(e.getMessage());
        } catch (Throwable e){
            BaasBoxLogger.error("Internal Scripts engine error",e);
            result = internalServerError(e.getMessage());
        }
        if (BaasBoxLogger.isTraceEnabled()) BaasBoxLogger.trace("End Method");
        return result;
    }

    @BodyParser.Of(BodyParser.Json.class)
    public static Result create(){
        if (BaasBoxLogger.isTraceEnabled()) BaasBoxLogger.trace("Start Method");
        Http.Request req = request();
        BaasBoxLogger.debug("Creating a new plugin. Received {}",req.toString());
        JsonNode body = req.body().asJson();
        Result result;
        try {
            validateBody(body);
            ScriptStatus res = ScriptingService.create(body);
            if (res.ok){
                result = created(res.message);
            } else {
                // todo check return code
                result = ok(res.message);
            }
        } catch (ScriptAlreadyExistsException e) {
            result = badRequest(e.getMessage());
        }catch (ScriptException e) {
            String message = e.getMessage();

            result = badRequest(message==null?"Script error":message);
        }

        if (BaasBoxLogger.isTraceEnabled()) BaasBoxLogger.trace("End Method");
        return result;
    }

    private static void validateBody(JsonNode body) throws ScriptException{
        if (body == null){
            throw new ScriptException("Missing body");
        }
        JsonNode name = body.get(ScriptsDao.NAME);
        if (name == null || (!name.isTextual())|| name.asText().trim().length()==0) {
            throw new ScriptException("Missing required 'name' property");
        }
        JsonNode code = body.get(ScriptsDao.CODE);
        if (code == null||(!code.isTextual())||code.asText().trim().length()==0) {
            throw new ScriptException("Missing required 'code' property");
        }
        JsonNode lang = body.get(ScriptsDao.LANG);
        if (lang == null||(!lang.isTextual())||lang.asText().trim().length()==0) {
            throw new ScriptException("Missing required 'lang' property or it is not a string");
        }
        if (ScriptLanguage.forName(lang.asText())==null){
        	throw new ScriptException("Language '"+lang.asText()+"' is not supported");
        }
        JsonNode encoded = body.get(ScriptsDao.ENCODED);
        if (encoded != null){
        	if (!encoded.isTextual()){
        		throw new ScriptException("The field 'encoded' must be a String");
        	}
        	try{
        		ScriptsDao.ENCODED_TYPE.valueOf(encoded.asText().toUpperCase());
        	}catch(IllegalArgumentException e){
        		throw new ScriptException("The specified 'encoded' value is not valid");
        	} 
        }
    }

    public static Result list(){
        if (BaasBoxLogger.isTraceEnabled()) BaasBoxLogger.trace("Method Start");
        Result result;
        try {
        	Context ctx=Http.Context.current.get();
			QueryParams criteria = (QueryParams) ctx.args.get(IQueryParametersKeys.QUERY_PARAMETERS);
            List<ODocument> documents = ScriptingService.list(criteria);
            String json = JSONFormats.prepareResponseToJson(documents, JSONFormats.Formats.DOCUMENT);
            result = ok(json);
        } catch (SqlInjectionException e) {
            BaasBoxLogger.error("Sql injection: ",e);
            result = badRequest(e.getMessage());
        } catch (IOException e) {
            BaasBoxLogger.error("Error formatting response: ",e);
            result = internalServerError(e.getMessage());
        }
        if (BaasBoxLogger.isTraceEnabled()) BaasBoxLogger.trace("Method End");
        return result;
    }

    public static Result get(String name){
        if (BaasBoxLogger.isTraceEnabled()) BaasBoxLogger.trace("Method Start");
        Result result = null;
        ODocument script = ScriptingService.get(name);
        if (script != null){
            result = ok(JSONFormats.prepareResponseToJson(script, JSONFormats.Formats.JSON));
        } else {
            result = notFound("Script: "+name+ " not found");
        }
        if (BaasBoxLogger.isTraceEnabled()) BaasBoxLogger.trace("Method End");
        return result;
    }

    public static Result drop(String name){
        if (BaasBoxLogger.isTraceEnabled()) BaasBoxLogger.trace("Method Start");
        Result res;
        try {
            boolean deleted = ScriptingService.forceDelete(name);
            if (deleted){
                res = ok();
            } else {
                res = notFound("script: "+name+" not found");
            }
        } catch (ScriptException e){
            BaasBoxLogger.error("Error while deleting script: "+name,e);
            res = internalServerError(e.getMessage());
        }
        if (BaasBoxLogger.isTraceEnabled())BaasBoxLogger.trace("Method End");
        return res;
    }

    public static Result delete(String name){
        if (BaasBoxLogger.isTraceEnabled()) BaasBoxLogger.trace("Method Start");
        Result result;
        try {
            boolean deleted =ScriptingService.delete(name);
            if (deleted){
                result = ok();
            } else {
                result = notFound("script: "+name+" not found");
            }
        } catch (ScriptException e) {
            BaasBoxLogger.error("Error while deleting script: "+name,e);
            result = internalServerError(e.getMessage());
        }
        if (BaasBoxLogger.isTraceEnabled())BaasBoxLogger.trace("Method end");
        return result;
    }




//    public static Result log(final String name){
//        ODocument fn = ScriptingService.get(name);
//        if (fn == null){
//            return notFound("function not found");
//        } else {
//            return ok(new EventSource() {
//                @Override
//                public void onConnected() {
//
//                    final EventSource current =this;
//                    this.onDisconnected(new F.Callback0() {
//                        @Override
//                        public void invoke() throws Throwable {
//                            ScriptingService.disconnectLogListener(name, current);
//                        }
//                    });
//                    ScriptingService.connectLogListener(name,current);
//                }
//            });
//        }
//    }
}
