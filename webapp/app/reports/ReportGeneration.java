package reports;

import be.ugent.degage.db.DataAccessContext;
import be.ugent.degage.db.models.*;
import com.itextpdf.text.*;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;
import controllers.util.FileHelper;
import org.joda.time.DateTime;

import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.Period;

/**
 * Generates PDF
 */
public class ReportGeneration {

    // TODO: do not use static methods but use an object instead of parameters
    // TODO: move to db module or separate pdf module?
    public static void generateReceipt(DataAccessContext context, User user, Instant date, Costs costInfo) {
        try {
            Document document = new Document();
            String name = user.getId() + "." + date;
            String filename = FileHelper.getGeneratedFilesPath(name + ".pdf", "receipts");
            PdfWriter.getInstance(document, new FileOutputStream(filename));
            document.open();

            context.getReceiptDAO().createReceipt(
                    name,
                    new DateTime(date),
                    context.getFileDAO().createFile(filename, name, "pdf"),
                    user,
                    generatePDF(context, user, date, document, costInfo)
            );
            document.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private static BigDecimal generatePDF(DataAccessContext context, User user, Instant report, Document document, Costs costInfo) {

        // TODO: reduce the number of data base access calls
        BigDecimal saldo = BigDecimal.ZERO;
        try {
            String imageUrl = "https://fbcdn-sphotos-e-a.akamaihd.net/hphotos-ak-frc3/t1.0-9/969296_656566794396242_1002112915_n.jpg";
            // TODO: get this image from elsewhere!
            Image image = Image.getInstance(new URL(imageUrl));
            image.setAlignment(Image.LEFT | Image.TEXTWRAP);
            image.scaleAbsolute(60f, 60f);
            document.add(image);

            Date date = new Date(java.util.Date.from(report).getTime());

            PdfPTable table = new PdfPTable(3);
            add(table, "Afrekening n°:");
            add(table, user.getId() + "." + date, true);
            add(table, "");
            add(table, "Naam:");
            add(table, "" + user, true);
            add(table, "");
            add(table, "Adres:");
            add(table, "" + user.getAddressDomicile(), true);
            add(table, "");
            add(table, "Periode:", false, false);
            add(table, "vanaf " + new SimpleDateFormat("dd-MM-yyyy").format(report.minus(Period.ofMonths(3))), false, false);
            add(table, "t.e.m. " + new SimpleDateFormat("dd-MM-yyyy").format(report.minus(Period.ofDays(1))), false, false);

            table.setSpacingAfter(20);

            document.add(table);

            int userId = user.getId();
            saldo = createLoanerTable(
                    context.getCarRideDAO().getBillRidesForLoaner(date, userId),
                    context.getRefuelDAO().getBillRefuelsForLoaner(date, userId),
                    document,
                    costInfo);

            for (Car car : context.getCarDAO().listCarsOfUser(userId)) {
                int carId = car.getId();

                saldo = saldo.add(createCarTable(
                        context.getCarRideDAO().getBillRidesForCar(date, carId),
                        context.getRefuelDAO().getBillRefuelsForCar(date, carId),
                        context.getCarCostDAO().getBillCarCosts(date, carId),
                        document,
                        car.getName(),
                        costInfo));
            }

            Font f = new Font(Font.FontFamily.COURIER, 8);
            Font f2 = new Font(Font.FontFamily.COURIER, 6);
            document.add(new Paragraph("Rekeningnummer 523-080452986-86 -IBAN BE78 5230 8045 -BIC Code TRIOBEBB", f));
            document.add(new Paragraph("Degage! vzw - Fuchsiastraat 81, 9000 Gent", f));
            document.add(new Paragraph("Gelieve de afrekening te betalen voor " + new SimpleDateFormat("dd-MM-yyyy").format(report.plus(Period.ofMonths(3))), f));
            document.add(new Paragraph("Bij betaling, gelieve het nummer van de afrekening te vermelden", f2));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return saldo;
    }

    private static BigDecimal createCarTable(Iterable<CarRide> rides, Iterable<Refuel> refuels, Iterable<CarCost> carCosts, Document document, String carName, Costs costInfo)
            throws DocumentException {
        document.newPage();
        document.add(new Paragraph("WAGEN: " + carName));
        PdfPTable carTable = new PdfPTable(2);
        carTable.setSpacingBefore(5);
        carTable.setSpacingAfter(10);

        int loanerDist = 0;
        int othersDist = 0;

        for (CarRide ride : rides) {
            if (ride.getReservation().getUser() == ride.getReservation().getCar().getOwner()) {
                loanerDist += ride.getEndMileage() - ride.getStartMileage();
            } else {
                othersDist += ride.getEndMileage() - ride.getStartMileage();
            }
        }

        add(carTable, "Totaal aantal kilometers:", true);
        add(carTable, (loanerDist + othersDist) + " km", true);

        // TODO: date
        double deprecation = costInfo.getDeprecation();

        if (loanerDist + othersDist > 0) {
            add(carTable, "Door eigenaar gereden:");
            add(carTable, loanerDist + " km");
            add(carTable, "Percentage eigenaar:");
            add(carTable, Math.round(100 * (double) loanerDist / (double) (loanerDist + othersDist)) + "%");
            add(carTable, "Percentage gedeeld:");
            add(carTable, Math.round(100 * (double) othersDist / (double) (loanerDist + othersDist)) + "%");
            add(carTable, "Afschrijving per kilometer:");
            add(carTable, "€ " + deprecation);

            deprecation *= (double) othersDist;
            add(carTable, "Afschrijving voor deze periode:", true);
            add(carTable, "€ " + deprecation, true);
        }

        add(carTable, "");
        add(carTable, "");

        BigDecimal carCostAmount = BigDecimal.ZERO;
        for (CarCost carcost : carCosts) {
            carCostAmount = carCostAmount.add(carcost.getAmount());
        }

        add(carTable, "Vaste kosten af te schrijven:");
        add(carTable, "€ " + carCostAmount);

        BigDecimal recupCosts = BigDecimal.ZERO;

        if (loanerDist + othersDist > 0) {
            recupCosts = (carCostAmount.multiply(new BigDecimal(othersDist)).divide(new BigDecimal(loanerDist + othersDist)));
            add(carTable, "Recuperatie vaste kosten:", true);
            add(carTable, "€ " + recupCosts, true);
            add(carTable, "Ter info: Vaste kost per kilometer:");
            add(carTable, "€ " + carCostAmount.divide(new BigDecimal(loanerDist + othersDist)));
        }

        add(carTable, "");
        add(carTable, "");

        BigDecimal refuelOthers = BigDecimal.ZERO;
        BigDecimal refuelOwner = BigDecimal.ZERO;

        for (Refuel refuel : refuels) {
            if (refuel.getCarRide().getCost().compareTo(BigDecimal.ZERO) == 0) {
                refuelOwner = refuelOwner.add(refuel.getAmount());
            } else {
                refuelOthers = refuelOthers.add(refuel.getAmount());
            }
        }

        add(carTable, "Totaal brandstof:");
        add(carTable, "€ " + (refuelOthers.add(refuelOwner)));

        BigDecimal refuelTot = BigDecimal.ZERO;

        if (loanerDist + othersDist > 0) {
            add(carTable, "Brandstof per kilometer:");
            add(carTable, "€ " + refuelOthers.add(refuelOwner).divide(new BigDecimal(loanerDist + othersDist)));
            add(carTable, "Te betalen brandstof:");
            add(carTable, "€ " + refuelOthers.add(refuelOwner).multiply(new BigDecimal(loanerDist)).divide(new BigDecimal(loanerDist + othersDist)));
            add(carTable, "Brandstof reeds betaald:");
            add(carTable, "€ " + refuelOwner);
            refuelTot = refuelOwner.subtract(refuelOthers.add(refuelOwner).multiply(new BigDecimal(loanerDist)).divide(new BigDecimal(loanerDist + othersDist)));
            add(carTable, "Saldo brandstof:", true);
            add(carTable, "€ " + refuelTot, true);
        }

        add(carTable, "");
        add(carTable, "");

        BigDecimal total = recupCosts.add(refuelTot).add(new BigDecimal(deprecation));
        add(carTable, "SALDO WAGEN '" + carName + "'", true);
        add(carTable, "€ " + total, true);

        document.add(carTable);

        return total;
    }

    private static BigDecimal createLoanerTable(Iterable<CarRide> rides, Iterable<Refuel> refuels, Document document, Costs costInfo)
            throws DocumentException {
        document.add(new Paragraph("Ritten"));

        int levels = costInfo.getLevels();

        PdfPTable drivesTable = new PdfPTable(4 + levels);
        drivesTable.setWidthPercentage(100);
        drivesTable.setSpacingBefore(5);
        drivesTable.setSpacingAfter(10);

        add(drivesTable, "Auto", true, false);
        add(drivesTable, "Datum", true, false);
        add(drivesTable, "Afstand", true, false);

        int lower = 0;
        int upper = 0;

        for (int j = 0; j < levels; j++) {
            if (j > 0) {
                lower = upper;
            }

            if (j < levels - 1) {
                upper = costInfo.getLimit(j);
                add(drivesTable, lower + "-" + upper + " km", true, false);
            } else {
                add(drivesTable, "> " + upper + " km", true, false);
            }
        }
        add(drivesTable, "Ritprijs", true, false);

        add(drivesTable, "", true);
        add(drivesTable, "", true);
        add(drivesTable, "", true);

        for (int j = 0; j < levels; j++) {
            add(drivesTable, "€" + costInfo.getCost(j) + "/km");
        }

        add(drivesTable, "", true);

        int totalDistance = 0;
        BigDecimal totalCost = BigDecimal.ZERO;
        int[] totals = new int[levels];

        for (CarRide ride : rides) {

            int distance = ride.getEndMileage() - ride.getStartMileage();
            totalDistance += distance;

            add(drivesTable, ride.getReservation().getCar().getName());
            add(drivesTable, new SimpleDateFormat("dd-MM-yyyy").format(ride.getReservation().getFrom().toDate()));
            add(drivesTable, distance + " km");

            int level;
            lower = 0;
            for (level = 0; level < levels; level++) {
                int limit = 0;
                int d;

                // TODO: refactor
                if (level == levels - 1 || distance <= (limit = costInfo.getLimit(level))) {
                    d = distance;
                } else {
                    d = limit - lower;
                }

                totals[level] += d;
                distance -= d;
                add(drivesTable, d + " km");

                if (distance == 0) {
                    level++;
                    break;
                }

                lower = limit;
            }

            for (int i = level; i < levels; i++) {
                add(drivesTable, "");
            }

            totalCost = totalCost.add(ride.getCost());

            if (ride.getCost().compareTo(BigDecimal.ZERO) != 0) {
                add(drivesTable, "€ " + ride.getCost(), true);
            } else {
                add(drivesTable, "--", true);
            }
        }

        add(drivesTable, "TOTALEN", true);
        add(drivesTable, "");
        add(drivesTable, totalDistance + " km", true);

        for (int j = 0; j < levels; j++) {
            add(drivesTable, totals[j] + " km", true);
        }

        add(drivesTable, "€ " + totalCost, true);

        document.add(drivesTable);

        document.add(new Paragraph("Tankbeurten"));
        PdfPTable refuelsTable = new PdfPTable(3);
        refuelsTable.setWidthPercentage(100);
        refuelsTable.setSpacingBefore(5);
        refuelsTable.setSpacingAfter(10);

        add(refuelsTable, "Auto", true);
        add(refuelsTable, "Datum", true);
        add(refuelsTable, "Prijs", true);

        BigDecimal refuelTotal = BigDecimal.ZERO;

        for (Refuel refuel : refuels) {
            add(refuelsTable, refuel.getCarRide().getReservation().getCar().getName());
            add(refuelsTable, new SimpleDateFormat("dd-MM-yyyy").format(refuel.getCarRide().getReservation().getFrom().toDate()));
            if (refuel.getCarRide().getCost().compareTo(BigDecimal.ZERO) != 0) {
                add(refuelsTable, "€ " + refuel.getAmount(), true);
                refuelTotal = refuelTotal.add(refuel.getAmount());
            } else {
                add(refuelsTable, "-- € " + refuel.getAmount(), true);
            }
        }

        add(refuelsTable, "TOTAAL", true);
        add(refuelsTable, "");
        add(refuelsTable, "€ " + refuelTotal, true);

        document.add(refuelsTable);

        PdfPTable totalTable = new PdfPTable(3);
        totalTable.setSpacingBefore(5);
        totalTable.setSpacingAfter(10);

        add(totalTable, "Totaal ritten", true);
        add(totalTable, "Totaal tankbeurten", true);
        add(totalTable, "SALDO", true);

        add(totalTable, "+ € " + totalCost);
        add(totalTable, "- € " + refuelTotal);
        add(totalTable, "€ " + totalCost.subtract(refuelTotal), true);

        document.add(totalTable);

        return totalCost.subtract(refuelTotal);
    }

    private static void add(PdfPTable table, String contents, boolean fat) {
        add(table, contents, fat, true);
    }

    private static void add(PdfPTable table, String contents, boolean fat, boolean border) {
        Font f = new Font(Font.FontFamily.COURIER, 8);
        if (fat) {
            f = new Font(Font.FontFamily.COURIER, 8, Font.BOLD);
        }
        PdfPCell cell = new PdfPCell(new Paragraph(contents, f));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        if (border) {
            cell.setPaddingBottom(5);
            cell.setBorder(Rectangle.BOTTOM);
            cell.setBorderColor(BaseColor.BLACK);
        } else {
            cell.setBorder(Rectangle.NO_BORDER);
        }
        table.addCell(cell);
    }

    private static void add(PdfPTable table, String contents) {
        add(table, contents, false);
    }
}