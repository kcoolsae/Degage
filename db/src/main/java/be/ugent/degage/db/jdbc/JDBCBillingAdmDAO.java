/* JDBCBillingAdmDAO.java
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

package be.ugent.degage.db.jdbc;

import be.ugent.degage.db.DataAccessException;
import be.ugent.degage.db.dao.BillingAdmDAO;

import java.sql.CallableStatement;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * JDBC implementation of {@link BillingAdmDAO}
 */
class JDBCBillingAdmDAO extends AbstractDAO implements BillingAdmDAO {

    public JDBCBillingAdmDAO(JDBCDataAccessContext context) {
        super(context);
    }

    @Override
    public void archive(int billingId) {
        try (CallableStatement cs = prepareCall("{call billing_archive(?)}" )) {
            cs.setInt(1,billingId);
            cs.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Could not archive billing", e);
        }
    }

    @Override
    public void createBilling(String description, String prefix, LocalDate start, LocalDate limit) {
        try (PreparedStatement ps = prepareStatement(
                "INSERT INTO billing(billing_description,billing_prefix,billing_start,billing_limit) VALUES (?,?,?,?)"
        )) {
            ps.setString(1, description);
            ps.setString(2, prefix);
            ps.setDate(3, Date.valueOf(start));
            ps.setDate(4, Date.valueOf(limit));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Could not create billing", e);
        }
    }

    @Override
    public List<CarInfo> listCarBillingInfo(int billingId) {
        try (PreparedStatement ps = prepareStatement(
                "SELECT car_id, car_name, " +
                        "(car_deprec IS NULL OR car_deprec = 0 OR car_deprec_limit IS NULL OR car_deprec_limit = 0 " +
                        " OR car_deprec_last IS NULL OR car_deprec = 0) AS incomplete, d " +
                "FROM cars " +
                "LEFT JOIN ( SELECT reservation_car_id AS id, 1 as d FROM trips,billing " +
                        "WHERE reservation_status > 5 AND reservation_from < billing_limit AND billing_id = ? " + //[ENUM INDEX]
                        "UNION " +
                        "SELECT reservation_car_id AS id, 1 as d FROM refuels " +
                            "JOIN reservations ON reservation_id = refuel_car_ride_id " +
                            "JOIN billing " +
                        " WHERE refuel_status != 'REFUSED' AND NOT refuel_archived AND reservation_from < billing_limit AND billing_id = ? " +
                ") AS tmp ON car_id=id  ORDER BY car_name"
        )) {
            ps.setInt(1, billingId);
            ps.setInt(2, billingId);
            //System.err.println(ps);
            return toList(ps, rs -> {
                CarInfo info = new CarInfo();
                info.carId = rs.getInt("car_id");
                info.carName = rs.getString("car_name");
                info.incomplete = rs.getBoolean("incomplete");
                info.nodata = rs.getObject("d") == null;
                return info;
            });
        } catch (SQLException e) {
            throw new DataAccessException("Could not create billing", e);
        }
    }
}
