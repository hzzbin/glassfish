/**
* DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
* Copyright 2009 Sun Microsystems, Inc. All rights reserved.
* Generated code from the com.sun.enterprise.config.serverbeans.*
* config beans, based on  HK2 meta model for these beans
* see generator at org.admin.admin.rest.GeneratorResource
* date=Mon Apr 20 11:28:36 PDT 2009
* Very soon, this generated code will be replace by asm or even better...more dynamic logic.
* Ludovic Champenois ludo@dev.java.net
*
**/
package org.glassfish.admin.rest.resources;
import com.sun.enterprise.config.serverbeans.*;
import javax.ws.rs.*;
import java.util.List;
import org.glassfish.admin.rest.TemplateListOfResource;
import com.sun.enterprise.config.serverbeans.HttpListener;
public class ListHttpListenerResource extends TemplateListOfResource<HttpListener> {


	@Path("{Id}/")
	public HttpListenerResource getHttpListenerResource(@PathParam("Id") String id) {
		HttpListenerResource resource = resourceContext.getResource(HttpListenerResource.class);
		for (HttpListener c: entity){
			if(c.getId().equals(id)){
				resource.setEntity(c);
			}
		}
		return resource;
	}

}
