package core.commands;

import core.exceptions.InstanceNotFoundException;
import core.exceptions.LastFmException;
import core.parsers.Parser;
import core.parsers.UrlParser;
import core.parsers.params.UrlParameters;
import dao.ChuuService;
import dao.entities.RYMImportRating;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.apache.commons.text.StringEscapeUtils;

import java.io.IOException;
import java.net.URL;
import java.time.Year;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RYMDumpImportCommand extends ConcurrentCommand<UrlParameters> {
    private static final String headerLine = "RYM Album, First Name,Last Name,First Name localized, Last Name localized,Title,Release_Date,Rating,Ownership,Purchase Date,Media Type,Review";
    private static final Pattern unlocalized = Pattern.compile("(.*) \\[(.*)] ?");
    private static final Function<String, RYMImportRating> mapper = (line) -> {
        String[] split = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
        if (split.length != 12)
            return null;
        split = Arrays.stream(split).map(x -> {
            x = StringEscapeUtils.unescapeHtml4(x.substring(1, x.length() - 1));
            int i = x.indexOf('/');
            if (i != -1 && (i > 0.2 * x.length() || i < 0.9 * x.length())) {
                x = x.split("/")[0];
            }
            return x;
        }).toArray(String[]::new);
        try {
            long rymId = Long.parseLong(split[0]);
            String firstName = split[1];
            String lastName = split[2];
            String firstNameLocalized = split[3];
            String lastNameLocalized = split[4];
            String title = split[5];
            Year year = split[6].isEmpty() ? null : Year.parse(split[6]);
            Byte rating = Byte.valueOf(split[7]);
            boolean ownership = split[8].equals("Y");
            Year purchaseDate = split[9].isEmpty() ? null : Year.parse(split[9]);
            String mediaType = split[10];
            String review = split[11];
            Matcher matcher;
            if (firstName.isBlank() && firstNameLocalized.isBlank() && lastNameLocalized.isBlank() && (matcher = unlocalized.matcher(lastName)).matches()) {
                String group = matcher.group(1);
                String group1 = matcher.group(2);
                lastName = group;
                lastNameLocalized = group1;
            }
            return new RYMImportRating(rymId, firstName, lastName, firstNameLocalized, lastNameLocalized, title, year, rating, ownership, purchaseDate, mediaType, review);
        } catch (NumberFormatException | DateTimeParseException ex) {
            return null;
        }
    };

    public RYMDumpImportCommand(ChuuService dao) {
        super(dao);
    }

    @Override
    protected CommandCategory getCategory() {
        return CommandCategory.RYM_BETA;
    }

    @Override
    public Parser<UrlParameters> getParser() {
        return new UrlParser(false);
    }

    @Override
    public String getDescription() {
        return "Load you rym rating into the bot. Read the help message for info about how to do it";
    }

    @Override
    public List<String> getAliases() {
        return List.of("rymimport");
    }

    @Override
    public String getName() {
        return "RYM Import";
    }

    @Override
    void onCommand(MessageReceivedEvent e) throws LastFmException, InstanceNotFoundException {
        UrlParameters parse = parser.parse(e);
        if (parse == null) {
            return;
        }
        List<RYMImportRating> ratings = new ArrayList<>();
        String url = parse.getUrl();
        if (url.isBlank()) {
            sendMessageQueue(e, "You need to upload a file  :thinking:");
            return;
        }
        try {
            URL url1 = new URL(url);
            Scanner s = new Scanner(url1.openStream());
            if (!s.hasNextLine()) {
                sendMessageQueue(e, "File was empty :thinking:");
                return;
            }
            String next = s.nextLine();
            if (!next.equals(headerLine)) {
                sendMessageQueue(e, "File did not match rym export format :thinking:");
                return;
            }
            while (s.hasNextLine()) {
                String line = s.nextLine();
                RYMImportRating rating = mapper.apply(line);
                if (rating == null) {
                    sendMessageQueue(e, "File did not match rym export format :thinking:");
                    return;
                }
                if (rating.getRating() == 0) {
                    continue;
                }
                ratings.add(rating);
            }
        } catch (IOException ioException) {
            sendMessageQueue(e, "An Unexpected Error happened parsing the file :thinking:");
            return;
        }
        if (ratings.isEmpty()) {
            sendMessageQueue(e, "Rating List was empty :thinking:");
            return;
        }
        sendMessageQueue(e, String.format("Read %d ratings, now the import process will start.", ratings.size()));
        e.getChannel().sendTyping().queue();
        getService().insertRatings(e.getAuthor().getIdLong(), ratings);
        sendMessageQueue(e, "Finished without errors");
    }

    @Override
    public String getUsageInstructions() {
        return getAliases().get(0) + " rym_import_file \n" + " " +
                "In order to import your data you need to actively download your rym data and then link it to the bot uploading the plain .txt file while using this command." +
                "The file can be exported and the bottom of your profile page on rym clicking on the button ***EXPORT YOUR DATA*** or ***EXPROT WITH REVIEWS***";

    }
}
