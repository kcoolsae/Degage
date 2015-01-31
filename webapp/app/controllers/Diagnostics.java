/* Diagnostics.java
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
 * distribution).  If not, see http://www.gnu.org/licenses/.
 */

package controllers;

import be.ugent.degage.db.DataAccessException;
import be.ugent.degage.db.models.JobType;
import db.DataAccess;
import play.mvc.Controller;
import play.mvc.Result;
import db.InjectContext;

/**
 * Created by kc on 1/29/15.
 */
public class Diagnostics  extends Controller {

    /**
     * Just returns ok. Used to check whether the application is still alive
     * @return
     */
    public static Result checkApplication() {
        return ok();
    }

    /**
     * Contacts the database and returns OK if there is no exception
     */
    @InjectContext
    public static Result checkDatabase() {
        try {
            DataAccess.getInjectedContext().getJobDAO().ping();
            return ok();
        } catch (DataAccessException ex) {
            return internalServerError();
        }
    }
}