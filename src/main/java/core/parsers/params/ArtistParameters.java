package core.parsers.params;

import dao.entities.LastFMData;
import dao.entities.ScrobbledArtist;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

public class ArtistParameters extends ChuuDataParams {
    private final String artist;
    private ScrobbledArtist scrobbledArtist;


    public ArtistParameters(MessageReceivedEvent e, String artist, LastFMData user) {
        super(e, user);
        this.artist = artist;
    }

    public String getArtist() {
        return artist;
    }


    public ScrobbledArtist getScrobbledArtist() {
        return scrobbledArtist;
    }

    public void setScrobbledArtist(ScrobbledArtist scrobbledArtist) {
        this.scrobbledArtist = scrobbledArtist;
    }

    public boolean isNoredirect() {
        return hasOptional("--noredirect");
    }

}
