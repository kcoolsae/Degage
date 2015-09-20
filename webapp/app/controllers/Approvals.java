/* Approvals.java
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

import be.ugent.degage.db.DataAccessContext;
import be.ugent.degage.db.FilterField;
import be.ugent.degage.db.dao.*;
import be.ugent.degage.db.models.*;
import com.google.common.base.Strings;
import controllers.util.Pagination;
import db.CurrentUser;
import db.DataAccess;
import db.InjectContext;
import notifiers.Notifier;
import play.data.Form;
import play.data.validation.ValidationError;
import play.mvc.Controller;
import play.mvc.Result;
import views.html.approvals.*;

import java.util.*;

/**
 */
public class Approvals extends Controller {


    private static Iterable<String> checkApprovalConditions(int userId, DataAccessContext context) {
        UserDAO udao = context.getUserDAO();
        FileDAO fdao = context.getFileDAO();
        // TODO: minimize data transfer by using a query with true/false results
        User user = udao.getUser(userId); // gets the full user instead of small cached one

        Collection<String> errors = new ArrayList<>();
        if (Strings.isNullOrEmpty(user.getAddressDomicile().getStreet())) {
            errors.add("Domicilieadres ontbreekt.");
        }
        if (Strings.isNullOrEmpty(user.getAddressResidence().getStreet())) {
            errors.add("Verblijfsadres ontbreekt.");
        }
        if (user.getIdentityId() == null) {
            errors.add("Identiteitsgegevens ontbreken.");
        } else if (!fdao.hasUserFile(userId, FileDAO.UserFileType.ID)) {
            errors.add("Scan identiteitskaart ontbreekt");
        }
        if (user.getLicense() == null) {
            errors.add("Rijbewijs ontbreekt.");
        } else if (!fdao.hasUserFile(userId, FileDAO.UserFileType.LICENSE)) {
            errors.add("Ingescand rijbewijs ontbreekt");
        }
        if (!user.isPayedDeposit()) {
            errors.add("Waarborg nog niet betaald.");
        }
        if (user.getCellPhone() == null && user.getPhone() == null) {
            errors.add("Telefoon/GSM ontbreekt.");
        }
        return errors;
    }

    public static class RequestApprovalData {
        public String message;
        public boolean acceptsTerms;

        public List<ValidationError> validate() {
            if (!acceptsTerms) {
                return Collections.singletonList(
                        new ValidationError("acceptsTerms", "Gelieve de algemene voorwaarden te accepteren")
                );
            } else {
                return null;
            }
        }
    }

    /**
     * Method: GET
     * A page to request full user approval
     *
     * @return The page to request approval
     */
    @AllowRoles({})
    @InjectContext
    public static Result requestApproval() {
        if (CurrentUser.hasFullStatus()) {
            flash("warning", "Je bent reeds een volwaardig lid.");
            return redirect(routes.Application.index());
        } else {
            DataAccessContext context = DataAccess.getInjectedContext();
            if (context.getApprovalDAO().hasApprovalPending(CurrentUser.getId())) {
                flash("warning", "We nemen op dit moment je aanvraag om lid te worden in beschouwing.");
                return redirect(routes.Application.index());
            } else if (context.getInfoSessionDAO().getInfoSessionWherePresent(CurrentUser.getId()) == null) {
                flash("danger", "Je bent nog niet naar een infosessie geweest en kan dus nog geen lid worden.");
                return redirect(routes.InfoSessions.showUpcomingSessions());
            } else {
                return ok(approvalrequest.render(
                                checkApprovalConditions(CurrentUser.getId(), context),
                                Form.form(RequestApprovalData.class) )
                );
            }
        }
    }

    @AllowRoles({})
    @InjectContext
    public static Result requestApprovalPost() {
        Form<RequestApprovalData> form = Form.form(RequestApprovalData.class).bindFromRequest();
        DataAccessContext context = DataAccess.getInjectedContext();
        if (form.hasErrors()) {
            return badRequest(approvalrequest.render(
                            checkApprovalConditions(CurrentUser.getId(), context),
                            form
                    )
            );
        } else {
            Integer isp = context.getInfoSessionDAO().getInfoSessionWherePresent(CurrentUser.getId());
            if (isp == null) {
                flash("danger", "Je bent nog niet naar een infosessie geweest en kan dus nog geen lid worden.");
                return redirect(routes.InfoSessions.showUpcomingSessions());
            } else {
                context.getApprovalDAO().createApproval(CurrentUser.getId(), isp, form.get().message);
                context.getUserDAO().updateUserStatus(CurrentUser.getId(), UserStatus.FULL_VALIDATING);
                flash("success", "Bedankt voor de interesse. We nemen je aanvraag tot lidmaatschap in beraad.");
                return redirect(routes.Application.index());
            }
        }
    }

    @AllowRoles({UserRole.INFOSESSION_ADMIN, UserRole.PROFILE_ADMIN})
    @InjectContext
    public static Result pendingApprovalList() {
        return ok(approvals.render());
    }

    @AllowRoles({UserRole.INFOSESSION_ADMIN, UserRole.PROFILE_ADMIN})
    @InjectContext
    public static Result pendingApprovalListPaged(int page, int pageSize, int ascInt, String orderBy, String searchString) {
        // searchString not used
        DataAccessContext context = DataAccess.getInjectedContext();
        ApprovalDAO dao = context.getApprovalDAO();
        FilterField field = FilterField.stringToField(orderBy, FilterField.USER_NAME);
        boolean asc = Pagination.parseBoolean(ascInt);
        int amountOfResults = dao.getApprovalCount();
        return ok(approvalpage.render(dao.getApprovals(field, asc, page, pageSize),
                page,
                amountOfResults,
                (amountOfResults + pageSize - 1) / pageSize));
    }

    // used in injected context
    private static Result approvalForm(Approval ap, DataAccessContext context, Form<ApprovalAdminData> form, boolean bad) {
        EnrollementStatus status = EnrollementStatus.ABSENT;
        if (ap.getSession() != null) {
            InfoSessionDAO idao = context.getInfoSessionDAO();
            status = idao.getUserEnrollmentStatus(ap.getSession().getId(), ap.getUser().getId());
        }

        if (!bad) {
            return ok(approvaladmin.render(ap, status, checkApprovalConditions(ap.getUser().getId(), context), form));
        } else {
            return badRequest(approvaladmin.render(ap, status, checkApprovalConditions(ap.getUser().getId(), context), form));
        }
    }

    @AllowRoles({UserRole.INFOSESSION_ADMIN, UserRole.PROFILE_ADMIN})
    @InjectContext
    public static Result approvalDetails(int approvalId) {
        DataAccessContext context = DataAccess.getInjectedContext();
        ApprovalDAO dao = context.getApprovalDAO();
        Approval ap = dao.getApproval(approvalId);
        if (ap.getAdmin() == null) {
            flash("danger", "Gelieve eerst een contractverantwoordelijke op te geven.");
            return redirect(routes.Approvals.approvalAdmin(approvalId));
        } else {
            ApprovalAdminData data = new ApprovalAdminData();
            data.message = ap.getAdminMessage();
            if (ap.getStatus() == Approval.ApprovalStatus.ACCEPTED || ap.getStatus() == Approval.ApprovalStatus.PENDING) {
                data.status = ApprovalAdminData.Action.ACCEPT.name();
            } else {
                data.status = ApprovalAdminData.Action.DENY.name();
            }
            Set<UserRole> userRoles = DataAccess.getInjectedContext().getUserRoleDAO().getUserRoles(ap.getUser().getId());

            data.owner = userRoles.contains(UserRole.CAR_OWNER);

            return approvalForm(ap, context, Form.form(ApprovalAdminData.class).fill(data), false);
        }

    }

    @AllowRoles({UserRole.INFOSESSION_ADMIN, UserRole.PROFILE_ADMIN})
    @InjectContext
    public static Result approvalAdmin(int approvalId) {
        DataAccessContext context = DataAccess.getInjectedContext();
        ApprovalDAO dao = context.getApprovalDAO();
        Approval ap = dao.getApproval(approvalId);

        EnrollementStatus status = EnrollementStatus.ABSENT;
        if (ap.getSession() != null) {
            InfoSessionDAO idao = context.getInfoSessionDAO();
            status = idao.getUserEnrollmentStatus(ap.getSession().getId(), ap.getUser().getId());
        }
        InfoSessions.UserpickerData data = new InfoSessions.UserpickerData();
        data.populate(ap.getAdmin());
        return ok(setcontractadmin.render(ap, status, Form.form(InfoSessions.UserpickerData.class).fill(data)));
    }

    @AllowRoles({UserRole.INFOSESSION_ADMIN, UserRole.PROFILE_ADMIN})
    @InjectContext
    public static Result approvalAdminPost(int id) {
        DataAccessContext context = DataAccess.getInjectedContext();
        ApprovalDAO adao = context.getApprovalDAO();
        Approval app = adao.getApproval(id);
        Form<InfoSessions.UserpickerData> form = Form.form(InfoSessions.UserpickerData.class).bindFromRequest();
        if (form.hasErrors()) {
            // TODO: code in common with approvalAdmin
            EnrollementStatus status = EnrollementStatus.ABSENT;
            if (app.getSession() != null) {
                InfoSessionDAO idao = context.getInfoSessionDAO();
                status = idao.getUserEnrollmentStatus(app.getSession().getId(), app.getUser().getId());
            }
            return ok(setcontractadmin.render(app, status, form));
        } else {
            int userId = form.get().userId;

            UserDAO udao = context.getUserDAO();
            UserHeader contractManager = udao.getUserHeader(userId);

            Set<UserRole> userRoles = context.getUserRoleDAO().getUserRoles(userId);
            if (userRoles.contains(UserRole.INFOSESSION_ADMIN) || userRoles.contains(UserRole.SUPER_USER)) {
                // TODO: introduce hasRole method in DAO
                app.setAdmin(contractManager);
                adao.setApprovalAdmin(id, userId);

                Notifier.sendContractManagerAssignedMail(app.getUser(), app);
                flash("success", "De aanvraag werd successvol toegewezen aan " + contractManager);
                return redirect(routes.Approvals.pendingApprovalList());
            } else {
                flash("danger", contractManager + " heeft geen infosessie beheerdersrechten.");
                return redirect(routes.Approvals.approvalAdmin(id));
            }
        }
    }

    /**
     * Method: POST
     *
     * @param approvalId
     * @return
     */
    @AllowRoles({UserRole.INFOSESSION_ADMIN, UserRole.PROFILE_ADMIN})
    @InjectContext
    public static Result approvalAdminAction(int approvalId) {
        Form<ApprovalAdminData> form = Form.form(ApprovalAdminData.class).bindFromRequest();
        DataAccessContext context = DataAccess.getInjectedContext();
        ApprovalDAO dao = context.getApprovalDAO();
        Approval ap = dao.getApproval(approvalId);

        if (form.hasErrors()) {
            return approvalForm(ap, context, form, true);
        }

        ApprovalAdminData data = form.get();
        ApprovalAdminData.Action action = data.getAction();


        UserDAO udao = context.getUserDAO();
        ap.setAdmin(udao.getUserHeader(CurrentUser.getId()));
        ap.setAdminMessage(data.message);

        if (action == ApprovalAdminData.Action.ACCEPT) {

            ap.setStatus(Approval.ApprovalStatus.ACCEPTED);
            dao.updateApproval(ap);
            makeFullUser(ap.getUser().getId(), data.owner, data.message);
            flash("success", "De gebruiker is volwaardig lid geworden.");

            return redirect(routes.Approvals.pendingApprovalList());
        } else if (action == ApprovalAdminData.Action.DENY) {
            //TODO Warning, if status was not pending, possibly have to remove user roles
            ap.setStatus(Approval.ApprovalStatus.DENIED);
            dao.updateApproval(ap);
            Notifier.sendMembershipRejected(ap.getUser(), data.message);
            flash("success", "De aanvraag werd afgekeurd.");

            return redirect(routes.Approvals.pendingApprovalList());
        } else {
            return badRequest("Unspecified.");
        }


    }

    // to be used with injected context
    public static void makeFullUser(int userId, boolean owner, String message) {
        DataAccessContext context = DataAccess.getInjectedContext();
        UserDAO udao = context.getUserDAO();
        udao.makeUserFull(userId);

        // Add the new user roles
        UserRoleDAO roleDao = context.getUserRoleDAO();
        if (owner) {
            roleDao.addUserRole(userId, UserRole.CAR_OWNER);
        }
        roleDao.addUserRole(userId, UserRole.CAR_USER); // always
        Notifier.sendMembershipApproved(udao.getUserHeader(userId), message);
    }

    public static class ApprovalAdminData {
        public String message;
        public String status;
        public boolean owner;

        public enum Action {
            ACCEPT("Aanvaarden"),
            DENY("Verwerpen");

            private String description;

            Action(String description) {
                this.description = description;
            }

            @Override
            public String toString() {
                return description;
            }
        }

        public Action getAction() {
            return Enum.valueOf(Action.class, status);
        }
    }
}
