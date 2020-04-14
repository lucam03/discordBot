package core.commands;

import core.Chuu;
import core.apis.discogs.DiscogsApi;
import core.apis.discogs.DiscogsSingleton;
import core.apis.spotify.Spotify;
import core.apis.spotify.SpotifySingleton;
import core.exceptions.InstanceNotFoundException;
import core.exceptions.LastFmException;
import core.imagerenderer.GraphicUtils;
import core.imagerenderer.WhoKnowsMaker;
import core.imagerenderer.util.IPieable;
import core.imagerenderer.util.PieableKnows;
import core.otherlisteners.Reactionary;
import core.parsers.ArtistParser;
import core.parsers.OptionalEntity;
import core.parsers.Parser;
import core.parsers.params.ArtistParameters;
import dao.ChuuService;
import dao.entities.ReturnNowPlaying;
import dao.entities.ScrobbledArtist;
import dao.entities.WrapperReturnNowPlaying;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.imgscalr.Scalr;
import org.knowm.xchart.PieChart;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;


public class WhoKnowsCommand extends ConcurrentCommand<ArtistParameters> {
    private final DiscogsApi discogsApi;
    private final Spotify spotify;
    private final IPieable<ReturnNowPlaying, ArtistParameters> pie;

    public WhoKnowsCommand(ChuuService dao) {
        super(dao);
        this.discogsApi = DiscogsSingleton.getInstanceUsingDoubleLocking();
        this.spotify = SpotifySingleton.getInstance();
        this.pie = new PieableKnows(this.parser);
        this.respondInPrivate = false;

    }

    @Override
    public Parser<ArtistParameters> getParser() {
        return new ArtistParser(getService(), lastFM, new OptionalEntity("--list", "display in list format"));
    }

    @Override
    public String getDescription() {
        return "Returns List Of Users Who Know the inputted Artist";
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("whoknows", "wk", "whoknowsnp", "wknp");
    }

    @Override
    public void onCommand(MessageReceivedEvent e) throws LastFmException, InstanceNotFoundException {
        ArtistParameters artistParameters = parser.parse(e);
        if (artistParameters == null)
            return;
        ScrobbledArtist scrobbledArtist = new ScrobbledArtist(artistParameters.getArtist(), 0, null);
        CommandUtil.validate(getService(), scrobbledArtist, lastFM, discogsApi, spotify);
        artistParameters.setScrobbledArtist(scrobbledArtist);
        whoKnowsLogic(artistParameters);
    }

    private void doImage(ArtistParameters ap, WrapperReturnNowPlaying wrapperReturnNowPlaying) {
        MessageReceivedEvent e = ap.getE();
        wrapperReturnNowPlaying.getReturnNowPlayings().forEach(element ->
                element.setDiscordName(CommandUtil.getUserInfoNotStripped(e, element.getDiscordId()).getUsername())
        );
        BufferedImage logo = CommandUtil.getLogo(getService(), e);
        BufferedImage image = WhoKnowsMaker.generateWhoKnows(wrapperReturnNowPlaying, e.getGuild().getName(), logo);
        sendImage(image, e);

    }

    private void doList(ArtistParameters ap, WrapperReturnNowPlaying wrapperReturnNowPlaying) {
        EmbedBuilder embedBuilder = new EmbedBuilder();
        MessageBuilder messageBuilder = new MessageBuilder();
        StringBuilder builder = new StringBuilder();

        MessageReceivedEvent e = ap.getE();


        int counter = 1;
        for (ReturnNowPlaying returnNowPlaying : wrapperReturnNowPlaying.getReturnNowPlayings()) {
            builder.append(counter++)
                    .append(returnNowPlaying.toString());
            if (counter == 11)
                break;
        }
        embedBuilder.setTitle("Who knows " + CommandUtil.cleanMarkdownCharacter(ap.getScrobbledArtist().getArtist()) + " in " + CommandUtil.cleanMarkdownCharacter(e.getGuild().getName()) + "?").
                setThumbnail(CommandUtil.noImageUrl(wrapperReturnNowPlaying.getUrl())).setDescription(builder)
                .setColor(CommandUtil.randomColor());
        messageBuilder.setEmbed(embedBuilder.build()).sendTo(e.getChannel())
                .queue(message1 ->
                        new Reactionary<>(wrapperReturnNowPlaying
                                .getReturnNowPlayings(), message1, embedBuilder));
    }

    private void doPie(ArtistParameters ap, WrapperReturnNowPlaying returnNowPlaying) {
        PieChart pieChart = this.pie.doPie(ap, returnNowPlaying.getReturnNowPlayings());
        pieChart.setTitle("Who knows " + (ap.getScrobbledArtist().getArtist()) + " in " + (ap.getE().getGuild().getName()) + "?");
        BufferedImage bufferedImage = new BufferedImage(1000, 750, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bufferedImage.createGraphics();
        GraphicUtils.setQuality(g);

        pieChart.paint(g, 1000, 750);
        try {
            java.net.URL url = new java.net.URL(returnNowPlaying.getUrl());
            BufferedImage backgroundImage = Scalr.resize(ImageIO.read(url), 300);
            g.drawImage(backgroundImage, 10, 750 - 10 - backgroundImage.getHeight(), null);
        } catch (IOException ex) {
            Chuu.getLogger().warn(ex.getMessage(), ex);
        }
        sendImage(bufferedImage, ap.getE());
    }

    void whoKnowsLogic(ArtistParameters ap) throws InstanceNotFoundException, LastFmException {

        ScrobbledArtist who = ap.getScrobbledArtist();
        long artistId = who.getArtistId();
        boolean isList = ap.hasOptional("--list") || ap.hasOptional("--pie");
        WrapperReturnNowPlaying wrapperReturnNowPlaying =
                isList
                        ? this.getService().whoKnows(artistId, ap.getE().getGuild().getIdLong(), Integer.MAX_VALUE)
                        : this.getService().whoKnows(artistId, ap.getE().getGuild().getIdLong());
        if (wrapperReturnNowPlaying.getRows() == 0) {
            sendMessageQueue(ap.getE(), "No one knows " + CommandUtil.cleanMarkdownCharacter(who.getArtist()));
            return;
        }
        wrapperReturnNowPlaying.getReturnNowPlayings()
                .forEach(x -> x.setDiscordName(CommandUtil.getUserInfoNotStripped(ap.getE(), x.getDiscordId()).getUsername()));
        wrapperReturnNowPlaying.setUrl(who.getUrl());
        if (ap.hasOptional("--list")) {
            doList(ap, wrapperReturnNowPlaying);
            return;
        }
        if (ap.hasOptional("--pie")) {
            doPie(ap, wrapperReturnNowPlaying);
            return;
        }
        doImage(ap, wrapperReturnNowPlaying);
    }

    @Override
    public String getName() {
        return "Who Knows";
    }


}
