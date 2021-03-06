package core.imagerenderer.util;

import core.commands.CommandUtil;
import core.parsers.ArtistSongParser;
import core.parsers.Parser;
import core.parsers.params.ArtistAlbumParameters;
import core.parsers.params.ArtistParameters;
import dao.entities.ReturnNowPlaying;
import dao.entities.Track;
import org.knowm.xchart.PieChart;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class PieableTrack extends OptionalPie implements IPieable<Track, ArtistAlbumParameters> {

    public PieableTrack(Parser<?> parser) {
        super(parser);
    }


    @Override
    public PieChart fillPie(PieChart chart, ArtistAlbumParameters params, List<Track> data) {
        int total = data.stream().mapToInt(Track::getPlays).sum();
        int breakpoint = (int) (0.75 * total);
        AtomicInteger counter = new AtomicInteger(0);
        AtomicInteger acceptedCount = new AtomicInteger(0);
        fillSeries(chart,
                (x) -> x.getName() + " - " + x.getPlays() + CommandUtil.singlePlural(x.getPlays(), " play", " plays"),
                Track::getPlays,
                x -> {
                    if (acceptedCount.get() < 15 || (counter.get() < breakpoint && acceptedCount.get() < 20)) {
                        counter.addAndGet(x.getPlays());
                        acceptedCount.incrementAndGet();
                        return true;
                    } else {
                        return false;
                    }
                }, data);
        return chart;
    }

}
