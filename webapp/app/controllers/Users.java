package controllers;

import be.ugent.degage.db.Filter;
import be.ugent.degage.db.FilterField;
import be.ugent.degage.db.dao.UserDAO;
import be.ugent.degage.db.models.User;
import be.ugent.degage.db.models.UserRole;
import controllers.Security.RoleSecured;
import controllers.util.Pagination;
import db.DataAccess;
import db.InjectContext;
import play.mvc.Controller;
import play.mvc.Result;
import play.twirl.api.Html;
import providers.DataProvider;
import views.html.users.users;
import views.html.users.userspage;

import java.util.List;

/**
 * Created by HannesM on 27/03/14.
 */
public class Users extends Controller {

    /**
     * @return The users index-page with all users
     */
    @RoleSecured.RoleAuthenticated({UserRole.CAR_USER, UserRole.PROFILE_ADMIN})
    public static Result showUsers() {
        return ok(users.render());
    }

    /**
     * @param page         The page in the userlists
     * @param ascInt       An integer representing ascending (1) or descending (0)
     * @param orderBy      A field representing the field to order on
     * @param searchString A string witth form field1:value1,field2:value2 representing the fields to filter on
     * @return A partial page with a table of users of the corresponding page
     */
    @RoleSecured.RoleAuthenticated({UserRole.CAR_USER, UserRole.PROFILE_ADMIN})
    public static Result showUsersPage(int page, int pageSize, int ascInt, String orderBy, String searchString) {
        // TODO: orderBy not as String-argument?
        FilterField carField = FilterField.stringToField(orderBy);

        boolean asc = Pagination.parseBoolean(ascInt);
        Filter filter = Pagination.parseFilter(searchString);
        return ok(userList(page, pageSize, carField, asc, filter));
    }

    @InjectContext
    private static Html userList(int page, int pageSize, FilterField orderBy, boolean asc, Filter filter) {
        UserDAO dao = DataAccess.getInjectedContext().getUserDAO();

        if (orderBy == null) {
            orderBy = FilterField.USER_NAME;
        }
        List<User> listOfUsers = dao.getUserList(orderBy, asc, page, pageSize, filter);

        int amountOfResults = dao.getAmountOfUsers(filter);
        int amountOfPages = (int) Math.ceil(amountOfResults / (double) pageSize);

        return userspage.render(listOfUsers, page, amountOfResults, amountOfPages);
    }

    @RoleSecured.RoleAuthenticated({UserRole.SUPER_USER})
    @InjectContext
    public static Result impersonate(int userId) {
        if (session("impersonated") != null && !session("impersonated").isEmpty()) {
            flash("danger", "Je bent reeds in impersoneermodus.");
            return redirect(routes.Dashboard.index());
        } else {
            UserDAO dao = DataAccess.getInjectedContext().getUserDAO();
            User user = dao.getUser(userId, false);
            if (user != null) {
                if (DataProvider.getUserRoleProvider().hasRole(user, UserRole.SUPER_USER)) {
                    flash("danger", "Je kan geen superuser impersoneren.");
                    return redirect(routes.Dashboard.index());
                } else {
                    String originalEmail = session("email"); //TODO: migrate to userprovider also
                    DataProvider.getUserProvider().createUserSession(user);
                    session("impersonated", originalEmail);
                    return redirect(routes.Dashboard.index());
                }
            } else {
                flash("danger", "Deze gebruikersID bestaat niet.");
                return redirect(routes.Users.showUsers());
            }
        }
    }
}
