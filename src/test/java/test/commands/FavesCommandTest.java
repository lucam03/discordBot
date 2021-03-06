package test.commands;

import core.apis.discogs.DiscogsApi;
import core.apis.discogs.DiscogsSingleton;
import core.apis.last.ConcurrentLastFM;
import core.apis.last.LastFMFactory;
import core.apis.spotify.Spotify;
import core.apis.spotify.SpotifySingleton;
import core.commands.CommandUtil;
import core.exceptions.LastFmException;
import dao.entities.NowPlayingArtist;
import org.junit.Test;
import test.commands.parsers.NullReturnParsersTest;
import test.commands.utils.CommandTest;
import test.commands.utils.EmbedUtils;
import test.commands.utils.TestResources;

import java.util.regex.Pattern;

public class FavesCommandTest extends CommandTest {
	@Override
	public String giveCommandName() {
		return "!favs";
	}

	@Test
	@Override
	public void nullParserReturned() {
		NullReturnParsersTest.artistTimeFrameParser(COMMAND_ALIAS);
	}

	@Test
	public void normalFunctionallity() {
		String blacpink = TestResources.dao.getArtistUrl("BLACKPINK");
		EmbedUtils
				.testLeaderboardEmbed(COMMAND_ALIAS + " BLACKPINK", EmbedUtils.descriptionArtistRegexNoMarkDownLink, "${header}'s Top (.*) Tracks in (.*)",
						false, false, blacpink, Pattern.compile("Coudnt't find your fav tracks of BLACPINK!"));

		String url2 = TestResources.dao.getArtistUrl("My Bloody Valentine");

		EmbedUtils
				.testLeaderboardEmbed(COMMAND_ALIAS + " My Bloody Valentine" + " w", EmbedUtils.descriptionArtistRegexNoMarkDownLink, "${header}'s Top (.*) Tracks in (.*)",
						false, false, url2, Pattern
								.compile("Coudnt't find your fav tracks of My Bloody Valentine in the last week!"));
	}

	@Test
	public void edgeCasesParser() throws LastFmException {
		ConcurrentLastFM newInstance = LastFMFactory.getNewInstance();
		DiscogsApi discogsApi = DiscogsSingleton.getInstanceUsingDoubleLocking();
		Spotify spotify = SpotifySingleton.getInstance();
		NowPlayingArtist np = newInstance.getNowPlayingInfo("pablopita");
		String artistUrl = CommandUtil
				.getArtistImageUrl(TestResources.dao, np.getArtistName(), newInstance, discogsApi, spotify);
		EmbedUtils
				.testLeaderboardEmbed(COMMAND_ALIAS + " w", EmbedUtils.descriptionArtistRegexNoMarkDownLink, "${header}'s Top (.*) Tracks in (.*)",
						false, false, artistUrl, Pattern
								.compile("Coudnt't find your fav tracks of " + np.getArtistName() + " in the last week!"));

		np = newInstance.getNowPlayingInfo("guilleecs");
		artistUrl = CommandUtil
				.getArtistImageUrl(TestResources.dao, np.getArtistName(), newInstance, discogsApi, spotify);
		EmbedUtils
				.testLeaderboardEmbed(COMMAND_ALIAS + " w " + TestResources.ogJDA.getSelfUser()
								.getAsMention(), EmbedUtils.descriptionArtistRegexNoMarkDownLink, "${header}'s Top (.*) Tracks in (.*)",
						false, false, artistUrl, Pattern
								.compile("Coudnt't find your fav tracks of " + np.getArtistName() + " in the last week!"));
	}
}

