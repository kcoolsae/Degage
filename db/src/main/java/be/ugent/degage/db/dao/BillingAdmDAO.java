/* BillingAdmDAO.java
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

package be.ugent.degage.db.dao;

import java.time.LocalDate;
import java.util.List;

/**
 * Data access object for billing administration
 */
public interface BillingAdmDAO {

    /**
     * Finalize a billing period. Puts all related trips, costs and refuels into
     * archive mode.
     */
    public void archive(int billingId);

    /**
     * Create a new billing.
     */
    public void createBilling(String description, String prefix, LocalDate start, LocalDate limit);

    public static class CarInfo {
        public int carId;
        public String carName;
        public boolean nodata; // true if there is nothing to be billed for this car
        public boolean incomplete; // true if the depreciation information for that car is not complete
    }

    /**
     * Return information about cars that are eligible for billing
     */
    public List<CarInfo> listCarBillingInfo(int billingId);
}
