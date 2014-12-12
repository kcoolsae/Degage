/* EmailTemplateTest.java
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 * Copyright Ⓒ 2014-2015 Universiteit Gent
 * 
 * This file is part of the Degage Web Application
 * 
 * Corresponding author (see also AUTHORS.txt)
 * 
 * Kris Coolsaet
 * Department of Applied Mathematics, Computer Science and Statistics
 * Ghent University 
 * Krijgslaan 281-S9
 * B-9000 GENT Belgium
 * 
 * The Degage Web Application is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * The Degage Web Application is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with the Degage Web Application (file LICENSE.txt in the
 * distribution).  If not, see <http://www.gnu.org/licenses/>.
 */

package controllers;

import static org.junit.Assert.assertEquals;
import static play.mvc.Http.Status.OK;
import static play.mvc.Http.Status.UNAUTHORIZED;
import static play.test.Helpers.POST;
import static play.test.Helpers.callAction;
import static play.test.Helpers.fakeApplication;
import static play.test.Helpers.fakeRequest;
import static play.test.Helpers.running;
import static play.test.Helpers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import controllers.util.TestHelper;
import be.ugent.degage.db.models.EmailTemplate;
import be.ugent.degage.db.models.User;
import be.ugent.degage.db.models.UserRole;

import db.DataAccess;
import org.junit.Before;
import org.junit.Test;

import play.mvc.Http.Cookie;
import play.mvc.Result;
import be.ugent.degage.db.DataAccessContext;
import providers.DataProvider;
import be.ugent.degage.db.dao.TemplateDAO;

public class EmailTemplateTest {
	
	private TemplateDAO emailDAO;
	private User admin;
	private List<EmailTemplate> templates;
	private List<User> nonAdmins;
	private TestHelper helper;
	private Cookie cookie;
	
	
	@Before
	public void setUp(){
		helper = new TestHelper();
		helper.setTestProvider();
		DataAccessContext context = DataAccess.getContext();
		emailDAO = context.getTemplateDAO();
		templates = emailDAO.getAllTemplates();
		nonAdmins = new ArrayList<>();
		admin = helper.createRegisteredUser("admin@test.com", "1234piano", "Pol", "Thijs",new UserRole[]{UserRole.MAIL_ADMIN});
		int i=0;
		for(UserRole role : UserRole.values()){
			i++;
			if(role!=UserRole.MAIL_ADMIN && role!=UserRole.SUPER_USER){
				nonAdmins.add(helper.createRegisteredUser("gebruiker"+i+"@nonadmin.com", "1234piano", "Jan", "Peeters", new UserRole[]{role}));
			}
		}
	}
	
	@Test
	public void editTemplateSucces(){
		running(fakeApplication(),new Runnable() {
			
			@Override
			public void run() {
				helper.setTestProvider();
				cookie = helper.login(admin,"1234piano");
				for(EmailTemplate template : templates){
					// Individueel template laten zien
					Result result = callAction(
			                controllers.routes.ref.EmailTemplates.showTemplate(template.getId()),
			                fakeRequest().withCookies(cookie)
			        );
					assertEquals("Show individual template", OK, status(result));
					
					Map<String,String> data = new HashMap<>();
					data.put("template_id", ""+template.getId());
					data.put("template_body", "Dit is de nieuwe body voor de template met id: " + template.getId() + ".");
					data.put("template_subject", "Geen onderwerp");
					
					// template aanpassen
					Result result1 = callAction(
			                controllers.routes.ref.EmailTemplates.editTemplate(),
			                fakeRequest(POST,"/emailtemplate/edit")
			        );
					assertEquals("Editing template", 303, status(result1));
				}
				helper.logout();
			}
		});
		
		
	}
	
	@Test
	public void showTemplateSucces(){
		running(fakeApplication(),new Runnable() {
			
			@Override
			public void run() {
				helper.setTestProvider();
				cookie = helper.login(admin,"1234piano");
				// Alle templates laten zien
				Result result = callAction(
		                controllers.routes.ref.EmailTemplates.showExistingTemplates(),
		                fakeRequest().withCookies(cookie)
		        );
		        assertEquals("Show email templates", OK, status(result));
		        helper.logout();
			}	
		});
		
	}
	
	@Test
	public void showTemplateFail(){
		running(fakeApplication(),new Runnable() {
			
			@Override
			public void run() {
				helper.setTestProvider();
				for(User user : nonAdmins){
					cookie = helper.login(user,"1234piano");
					// Alle templates laten zien, moet falen want we hebben niet de juiste rechten
					Result result = callAction(
			                controllers.routes.ref.EmailTemplates.showExistingTemplates(),
			                fakeRequest().withCookies(cookie)
			        );
			        assertEquals("Show email templates", UNAUTHORIZED, status(result));
			        helper.logout();
				}
			}
		});
		
	}
	
	@Test
	public void editTemplateFail(){
		running(fakeApplication(),new Runnable() {
			
			@Override
			public void run() {
				helper.setTestProvider();
				for(User user : nonAdmins){
					cookie = helper.login(user,"1234piano");
					// Template proberen editen, moet falen want we hebben niet de juiste rechten
					Result result = callAction(
			                controllers.routes.ref.EmailTemplates.editTemplate(),
			                fakeRequest().withCookies(cookie)
			        );
			        assertEquals("Show email templates", UNAUTHORIZED, status(result));
			        helper.logout();
				}
			}
		});
		
	}
}
