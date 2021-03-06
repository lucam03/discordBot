package core.commands;

import core.exceptions.InstanceNotFoundException;
import core.exceptions.LastFmException;
import core.parsers.PaceParser;
import core.parsers.Parser;
import core.parsers.params.NaturalTimeParams;
import core.parsers.params.NumberParameters;
import dao.ChuuService;
import dao.entities.NaturalTimeFrameEnum;
import dao.entities.UserInfo;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static core.parsers.ExtraParser.INNER_ERROR;
import static core.parsers.ExtraParser.LIMIT_ERROR;

/**
 * Credits: to lfmwhoknows bot owner for the idea
 */
public class PaceCommand extends ConcurrentCommand<NumberParameters<NumberParameters<NaturalTimeParams>>> {
    public PaceCommand(ChuuService dao) {
        super(dao);

    }
    @Override
    protected CommandCategory getCategory() {
        return CommandCategory.USER_STATS;
    }
    @Override
    //XD
    public Parser<NumberParameters<NumberParameters<NaturalTimeParams>>> getParser() {
        Map<Integer, String> map = new HashMap<>(2);
        map.put(INNER_ERROR, "The number introduced must be lower");
        map.put(LIMIT_ERROR, "You introduced a real big number");
        return new PaceParser(getService(), map);
    }

    @Override
    public String getDescription() {
        return "Pace";
    }

    @Override
    public List<String> getAliases() {
        return List.of("pace");
    }

    @Override
    public String getName() {
        return "Pace";
    }

    @Override
    void onCommand(MessageReceivedEvent e) throws LastFmException, InstanceNotFoundException {
        NumberParameters<NumberParameters<NaturalTimeParams>> hopefullyNoOneSeesThis = parser.parse(e);

        if (hopefullyNoOneSeesThis == null) {
            return;
        }
        NumberParameters<NaturalTimeParams> unitCount = hopefullyNoOneSeesThis.getInnerParams();
        NaturalTimeParams naturalTimeParams = unitCount.getInnerParams();
        String lastfmId = naturalTimeParams.getLastFMData().getName();
        long discordId = naturalTimeParams.getLastFMData().getDiscordId();
        NaturalTimeFrameEnum naturalTimeFrameEnum = naturalTimeParams.getTime();


        List<UserInfo> holder = lastFM.getUserInfo(List.of(lastfmId));
        UserInfo mainUser = holder.get(0);
        int playCount = mainUser.getPlayCount();
        String userString = getUserString(e, discordId, lastfmId);
        long unitNumber;
        long goal;
        Long tempS = hopefullyNoOneSeesThis.getExtraParam();
        Long tempU = unitCount.getExtraParam();

        if (tempU == null) {
            if (tempS == null) {
                // Both null we assume next 000 milestone and thats all
                unitNumber = 1;
                goal = (long) (Math.ceil(playCount / 10_000.) * 10_000);
            } else {
                // we only have one null
                long s = tempS;
                if (s < playCount) {
                    unitNumber = s;
                    goal = (long) (Math.ceil(playCount / 10_000.) * 10_000);
                } else {
                    goal = s;
                    unitNumber = 1;
                }
            }
        } else {
            unitNumber = tempU;
            goal = tempS;
        }

        // UTC was not working with last.fm smh
        ZonedDateTime now = LocalDateTime.now().atZone(ZoneOffset.ofHours(2));
        int timestamp;
        switch (naturalTimeFrameEnum) {
            case YEAR:
                timestamp = (int) now.minus(unitNumber, ChronoUnit.YEARS).toInstant().getEpochSecond();
                break;
            case QUARTER:
                timestamp = (int) now.minus(unitNumber * 4, ChronoUnit.MONTHS).toInstant().getEpochSecond();
                break;
            case MONTH:
                timestamp = (int) now.minus(unitNumber, ChronoUnit.MONTHS).toInstant().getEpochSecond();
                break;
            case ALL:
                timestamp = 0;
                break;
            case SEMESTER:
                timestamp = (int) now.minus(unitNumber * 2, ChronoUnit.MONTHS).toInstant().getEpochSecond();
                break;
            case WEEK:
                timestamp = (int) now.minus(unitNumber, ChronoUnit.WEEKS).toInstant().getEpochSecond();
                break;
            case DAY:
                timestamp = (int) now.minus(unitNumber, ChronoUnit.DAYS).toInstant().getEpochSecond();
                break;
            case HOUR:
                timestamp = (int) now.minus(unitNumber, ChronoUnit.HOURS).toInstant().getEpochSecond();
                break;
            case MINUTE:
                timestamp = (int) now.minus(unitNumber, ChronoUnit.MINUTES).toInstant().getEpochSecond();
                break;
            case SECOND:
                timestamp = (int) now.minus(unitNumber, ChronoUnit.SECONDS).toInstant().getEpochSecond();
                break;
            default:
                throw new IllegalArgumentException();
        }
        int totalScrobbles = lastFM.getInfoPeriod(lastfmId, timestamp);
        if (totalScrobbles == 0) {
            sendMessageQueue(e, userString + " hasn't played anything in the last " + unitNumber + " " + naturalTimeFrameEnum.toString().toLowerCase());
            return;
        }
        int unixtimestamp = mainUser.getUnixtimestamp();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd MMM yyyy");
        long totalUnits;

        timestamp = totalScrobbles == playCount ? unixtimestamp : timestamp;
        totalUnits = 0;
        int i = -1;
        List<ChronoUnit> chronoUnits = List.of(ChronoUnit.DAYS, ChronoUnit.HOURS, ChronoUnit.MINUTES, ChronoUnit.SECONDS);
        Map<ChronoUnit, BiFunction<Temporal, Temporal, Long>> functions = Map.of(ChronoUnit.DAYS, ChronoUnit.DAYS::between, ChronoUnit.HOURS, ChronoUnit.HOURS::between, ChronoUnit.MINUTES, ChronoUnit.MINUTES::between, ChronoUnit.SECONDS, ChronoUnit.SECONDS::between);
        LocalDateTime compareTime = LocalDateTime.ofEpochSecond(timestamp, 0, ZoneOffset.ofHours(1));
        while (totalUnits == 0) {
            i++;
            if (i == chronoUnits.size()) {
                sendMessageQueue(e, "Couldn't calculate your pace");
                return;
            }
            totalUnits = functions.get(chronoUnits.get(i)).apply(compareTime, now);

        }
        double ratio = ((double) totalScrobbles) / totalUnits;
        double remainingUnits = (goal - playCount) / ratio;
        String timeFrame;
        if (naturalTimeFrameEnum.equals(NaturalTimeFrameEnum.ALL)) timeFrame = "overall";
        else
            timeFrame = "over the last" + (unitNumber == 1 ? "" : " " + unitNumber) + " " + (unitNumber == 1 ? naturalTimeFrameEnum.toString().toLowerCase() : naturalTimeFrameEnum.toString().toLowerCase() + "s");
        String format = now.plus((long) remainingUnits, chronoUnits.get(i)).format(formatter);
        String unit = chronoUnits.get(i).name().toLowerCase();
        String s = String.format("**%s** has a rate of **%s** scrobbles per %s %s, so they are on pace to hit **%d** scrobbles on **%s**.",
                userString, new DecimalFormat("#0.00").format(ratio),
                unit.substring(0, unit.length() - 1),
                timeFrame, goal, format);

        sendMessageQueue(e, s);
    }
}
