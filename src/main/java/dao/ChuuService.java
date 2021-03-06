package dao;

import com.sun.istack.NotNull;
import core.Chuu;
import dao.entities.RYMImportRating;
import dao.entities.ScoredAlbumRatings;
import core.exceptions.ChuuServiceException;
import core.exceptions.DuplicateInstanceException;
import core.exceptions.InstanceNotFoundException;
import dao.entities.*;
import dao.musicbrainz.AffinityDao;
import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.sql.Savepoint;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class ChuuService {
    private final SimpleDataSource dataSource;
    private final SQLQueriesDao queriesDao;
    private final AffinityDao affinityDao;

    private final UpdaterDao updaterDao;
    private final SQLRYMDao rymDao;
    private final UserGuildDao userGuildDao;

    public ChuuService(SimpleDataSource dataSource) {

        this.dataSource = dataSource;
        this.queriesDao = new SQLQueriesDaoImpl();
        this.userGuildDao = new UserGuildDaoImpl();
        this.affinityDao = new AffinityDaoImpl();
        this.rymDao = new SQLRYMDaoImpl();
        this.updaterDao = new UpdaterDaoImpl();
    }

    public ChuuService() {

        this.dataSource = new SimpleDataSource(true);
        this.rymDao = new SQLRYMDaoImpl();
        this.queriesDao = new SQLQueriesDaoImpl();
        this.affinityDao = new AffinityDaoImpl();
        this.userGuildDao = new UserGuildDaoImpl();
        this.updaterDao = new UpdaterDaoImpl();

    }

    public void updateUserTimeStamp(String lastFmName, Integer timestamp, Integer timestampControl) {
        try (Connection connection = dataSource.getConnection()) {
            updaterDao.setUpdatedTime(connection, lastFmName, timestamp, timestampControl);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    public void insertArtistDataList(List<ScrobbledArtist> list, String id) {
        try (Connection connection = dataSource.getConnection()) {

            try {

                /* Prepare connection. */

                connection.setAutoCommit(false);
                if (!list.isEmpty()) {
                    //delete everything first to have a clean start
                    updaterDao.deleteAllArtists(connection, id);
                    /* Do work. */
                    updaterDao.fillIds(connection, list);

                    Map<Boolean, List<ScrobbledArtist>> map = list.stream().peek(x -> x.setDiscordID(id)).collect(Collectors.partitioningBy(scrobbledArtist -> scrobbledArtist.getArtistId() == -1));
                    List<ScrobbledArtist> nonExistingId = map.get(true);
                    if (!nonExistingId.isEmpty()) {
                        nonExistingId.forEach(x -> updaterDao.insertArtistSad(connection, x));
                        //updaterDao.insertArtists(connection, nonExistingId);
                    }
                    List<ScrobbledArtist> scrobbledArtists = map.get(false);
                    scrobbledArtists.addAll(nonExistingId);
                    updaterDao.addSrobbledArtists(connection, scrobbledArtists);
                }
                updaterDao.setUpdatedTime(connection, id, null, null);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw new ChuuServiceException(e);
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void incrementalUpdate(TimestampWrapper<List<ScrobbledArtist>> wrapper, String id) {
        try (Connection connection = dataSource.getConnection()) {

            try {

                /* Prepare connection. */
                connection.setAutoCommit(false);
                List<ScrobbledArtist> peeked = wrapper.getWrapped().stream().peek(x -> x.setDiscordID(id)).collect(Collectors.toList());
                updaterDao.upsertArtist(connection, peeked);
                updaterDao.setUpdatedTime(connection, id, wrapper.getTimestamp(), wrapper.getTimestamp());

                connection.commit();


            } catch (SQLException e) {
                connection.rollback();
                throw new ChuuServiceException(e);
            }

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void addGuildUser(long userID, long guildID) {
        try (Connection connection = dataSource.getConnection()) {
            userGuildDao.addGuild(connection, userID, guildID);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    public void insertNewUser(LastFMData data) {
        try (Connection connection = dataSource.getConnection()) {

            try {
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                connection.setAutoCommit(false);

                userGuildDao.insertUserData(connection, data);
                userGuildDao.addGuild(connection, data.getDiscordId(), data.getGuildID());
                connection.commit();

            } catch (SQLException e) {
                connection.rollback();
                throw new ChuuServiceException(e);
            } catch (RuntimeException e) {
                connection.rollback();
                throw e;
            }

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }


    @Deprecated
    public void updateLastFmData(long discordID, String lastFMID) {

        try (Connection connection = dataSource.getConnection()) {

            try {

                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                connection.setAutoCommit(false);

                LastFMData lastFmData = userGuildDao.findLastFmData(connection, discordID);

                lastFmData.setName(lastFMID);

                userGuildDao.updateLastFmData(connection, lastFmData);
                connection.commit();

            } catch (SQLException e) {
                connection.rollback();
                throw new ChuuServiceException(e);
            } catch (RuntimeException | Error e) {
                connection.rollback();
                throw e;
            } catch (InstanceNotFoundException e) {
                Chuu.getLogger().warn(e.getMessage(), e);
            }

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public LastFMData findLastFMData(long discordID) throws InstanceNotFoundException {
        try (Connection connection = dataSource.getConnection()) {
            return userGuildDao.findLastFmData(connection, discordID);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public LastFMData computeLastFmData(long discordID, Long guildId) throws InstanceNotFoundException {
        try (Connection connection = dataSource.getConnection()) {
            return userGuildDao.findLastFmData(connection, discordID, guildId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public ResultWrapper<UserArtistComparison> getSimilarities(List<String> lastFmNames, int limit) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.similar(connection, lastFmNames, limit);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public ResultWrapper<UserArtistComparison> getSimilarities(List<String> lastfMNames) {
        return getSimilarities(lastfMNames, 10);
    }


    public WrapperReturnNowPlaying globalWhoKnows(long artistId, boolean includeBottedUsers, long ownerId) {
        return globalWhoKnows(artistId, 10, includeBottedUsers, ownerId);
    }

    public WrapperReturnNowPlaying globalWhoKnows(long artistId, int limit, boolean includeBottedUsers, long ownerId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            if (limit < 1)
                limit = 10;
            return queriesDao.getGlobalWhoKnows(connection, artistId, limit, includeBottedUsers, ownerId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public WrapperReturnNowPlaying whoKnows(long artistId, long guildId) {
        return whoKnows(artistId, guildId, 10);
    }

    public WrapperReturnNowPlaying whoKnows(long artistId, long guildId, int limit) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            if (limit < 1)
                limit = 10;
            return queriesDao.knows(connection, artistId, guildId, limit);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public UpdaterUserWrapper getLessUpdated() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return updaterDao.getLessUpdated(connection);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public List<UsersWrapper> getAll(long guildId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return userGuildDao.getAll(connection, guildId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public List<UsersWrapper> getAllALL() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return userGuildDao.getAll(connection);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public UniqueWrapper<ArtistPlays> getUniqueArtist(Long guildID, String lastFmId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.getUniqueArtist(connection, guildID, lastFmId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public List<Long> getGuildList(long userId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return userGuildDao.guildsFromUser(connection, userId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public ResultWrapper<ScrobbledArtist> getGuildTop(Long guildID, int limit, boolean doCount) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.getGuildTop(connection, guildID, limit, doCount);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public UniqueWrapper<ArtistPlays> getCrowns(String lastFmID, long guildID, int threshold) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.getCrowns(connection, lastFmID, guildID, threshold);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public int getGuildCrownThreshold(long guildId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.getGuildCrownThreshold(connection, guildId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public boolean getGuildEmbedConfig(long guildId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.getGuildConfigEmbed(connection, guildId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public Set<ScrobbledArtist> getNullUrls() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return updaterDao.selectNullUrls(connection, false);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public Set<ScrobbledArtist> getSpotifyNulledUrls() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return updaterDao.selectNullUrls(connection, true);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public String getArtistUrl(String url) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return updaterDao.getArtistUrl(connection, url);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);

        }
    }

    public void upsertUrl(String url, long artistId) {
        upsertUrl(url, artistId, Chuu.getPresence().getJDA().getSelfUser().getIdLong());
    }

    public void userInsertUrl(String url, long artistId, long discordId) {
        try (Connection connection = dataSource.getConnection()) {
            long urlId = updaterDao.upsertUrl(connection, url, artistId, discordId);
            updaterDao.castVote(connection, urlId, discordId, true);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);

        }

    }

    public void userInsertQueueUrl(String url, long artistId, long discordId) {
        try (Connection connection = dataSource.getConnection()) {
            updaterDao.upsertQueueUrl(connection, url, artistId, discordId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);

        }

    }

    private void upsertUrl(String url, long artistId, long discordId) {
        try (Connection connection = dataSource.getConnection()) {
            updaterDao.upsertUrl(connection, url, artistId, discordId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);

        }

    }

    public void upsertSpotify(String url, long artistId, long discordId) {
        try (Connection connection = dataSource.getConnection()) {
            updaterDao.updateUrlStatus(connection, artistId);
            if (url != null && !url.isBlank()) {
                updaterDao.upsertSpotify(connection, url, artistId, discordId);
            }
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void upsertSpotify(String url, long artistId) {
        this.upsertSpotify(url, artistId, Chuu.getPresence().getJDA().getSelfUser().getIdLong());
    }

    public void addLogo(long guildId, BufferedImage in) {
        try (Connection connection = dataSource.getConnection()) {
            userGuildDao.addLogo(connection, guildId, in);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);

        }

    }

    public void removeLogo(long guildId) {
        try (Connection connection = dataSource.getConnection()) {
            userGuildDao.removeLogo(connection, guildId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);

        }

    }

    public InputStream findLogo(long guildId) {
        try (Connection connection = dataSource.getConnection()) {
            return userGuildDao.findLogo(connection, guildId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);

        }

    }

    public long getDiscordIdFromLastfm(String lasFmName, long guildId) throws InstanceNotFoundException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return userGuildDao.getDiscordIdFromLastFm(connection, lasFmName, guildId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);

        }
    }

    public int getArtistPlays(long artistId, String whom) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.userPlays(connection, artistId, whom);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);

        }
    }

    public List<LbEntry> getGuildCrownLb(long guildId, int threshold) {

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.crownsLeaderboard(connection, guildId, threshold);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);

        }
    }

    public void removeUserFromOneGuildConsequent(long discordID, long guildID) {
        removeFromGuild(discordID, guildID);
        MultiValuedMap<Long, Long> map = getMapGuildUsers();
        if (!map.containsValue(discordID)) {
            Chuu.getLogger().info("No longer sharing any server with user {} : %n removing user", discordID);
            removeUserCompletely(discordID);
        }
    }

    public void removeUserCompletely(Long discordID) {

        try (Connection connection = dataSource.getConnection()) {
            try {
                /* Prepare connection. */

                connection.setAutoCommit(false);
                /* Do work. */
                userGuildDao.removeUser(connection, discordID);
                /* Commit. */
                connection.commit();
                Chuu.getLogger().info("Deleted User {} :At  {} ", discordID, LocalDateTime.now().format(DateTimeFormatter.ISO_DATE));
            } catch (SQLException e) {
                connection.rollback();
                throw new ChuuServiceException(e);
            } catch (RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    private void removeFromGuild(Long discordID, long guildID) {

        try (Connection connection = dataSource.getConnection()) {
            try {
                /* Prepare connection. */

                connection.setAutoCommit(false);
                /* Do work. */
                userGuildDao.removeUserGuild(connection, discordID, guildID);
                /* Commit. */
                connection.commit();

            } catch (SQLException e) {
                connection.rollback();
                throw new ChuuServiceException(e);
            } catch (RuntimeException | Error e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public MultiValuedMap<Long, Long> getMapGuildUsers() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return userGuildDao.getWholeUserGuild(connection);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);

        }
    }


    public UpdaterStatus getUpdaterStatusByName(String artist) throws InstanceNotFoundException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return updaterDao.getUpdaterStatus(connection, artist);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);

        }

    }

    public void updateImageStatus(long artistId, String url, boolean updateBir) {
        try (Connection connection = dataSource.getConnection()) {
            updaterDao.updateStatusBit(connection, artistId, updateBir, url);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }


    public void insertCorrection(long artistId, String correction) {

        try (Connection connection = dataSource.getConnection()) {
            updaterDao.insertCorrection(connection, artistId, correction);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public String findCorrection(String artist_id) {
        try (Connection connection = dataSource.getConnection()) {
            return updaterDao.findCorrection(connection, artist_id);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);

        }
    }


    public void updateMetric(Metrics metrics, long incrementalNewValue) {
        try (Connection connection = dataSource.getConnection()) {
            updaterDao.updateMetric(connection, metrics.getMetricId(), incrementalNewValue);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);

        }
    }

    public void updateMetrics(long value1, long value2, long value3, long value4) {
        try (Connection connection = dataSource.getConnection()) {
            updaterDao.updateMetric(connection, Metrics.AOTY_DISCOGS.getMetricId(), value1);
            updaterDao.updateMetric(connection, Metrics.AOTY_MB_NAME.getMetricId(), value2);
            updaterDao.updateMetric(connection, Metrics.AOTY_TOTAL.getMetricId(), value3);
            updaterDao.updateMetric(connection, Metrics.REQUESTED.getMetricId(), value4);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);

        }
    }

    public List<LbEntry> getUniqueLeaderboard(long guildId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.uniqueLeaderboard(connection, guildId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);

        }
    }

    public int getUserArtistCount(String lastfmId, int threshold) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.userArtistCount(connection, lastfmId, threshold);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public List<LbEntry> getArtistLeaderboard(long guildId, int threshold) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.artistLeaderboard(connection, guildId, threshold);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);

        }
    }

    public List<LbEntry> getObscurityRankings(long guildId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.obscurityLeaderboard(connection, guildId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public PresenceInfo getRandomArtistWithUrl() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.getRandomArtistWithUrl(connection);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }


    public int randomCount(@Nullable Long userId) {
        try (Connection connection = dataSource.getConnection()) {
            return (queriesDao.getRandomCount(connection, userId));
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public boolean randomUrlExists(String url) {
        try (Connection connection = dataSource.getConnection()) {
            return (updaterDao.findRandomUrlById(connection, url) != null);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public boolean addToRandomPool(RandomUrlEntity randomUrlEntity) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            if (updaterDao.findRandomUrlById(connection, randomUrlEntity.getUrl()) == null) {
                updaterDao.insertRandomUrl(connection, randomUrlEntity.getUrl(), randomUrlEntity
                        .getDiscordId(), randomUrlEntity.getGuildId());
                return true;
            }
            return false;
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public RandomUrlEntity getRandomUrl() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return updaterDao.getRandomUrl(connection);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public StolenCrownWrapper getCrownsStolenBy(String ogUser, String queriedUser, long guildId, int threshold) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.getCrownsStolenBy(connection, ogUser, queriedUser, guildId, threshold);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public UniqueWrapper<ArtistPlays> getUserAlbumCrowns(String lastfmId, long guildId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.getUserAlbumCrowns(connection, lastfmId, guildId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public List<LbEntry> albumCrownsLeaderboard(long guildId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.albumCrownsLeaderboard(connection, guildId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void insertAlbumCrown(long artistId, String album, long discordID, long guildId, int plays) {
        try (Connection connection = dataSource.getConnection()) {
            updaterDao.insertAlbumCrown(connection, artistId, album, discordID, guildId, plays);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void deleteAlbumCrown(String artist, String album, long discordID, long guildId) {
        try (Connection connection = dataSource.getConnection()) {
            updaterDao.deleteAlbumCrown(connection, artist, album, discordID, guildId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public Map<Long, Character> getGuildPrefixes() {
        try (Connection connection = dataSource.getConnection()) {
            return updaterDao.getGuildPrefixes(connection);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void addGuildPrefix(long guildID, Character prefix) {
        try (Connection connection = dataSource.getConnection()) {
            if (Chuu.getPrefixMap().containsKey(guildID)) {
                userGuildDao.createGuild(connection, guildID);
            }
            updaterDao.upsertGuildPrefix(connection, guildID, prefix);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public ObscuritySummary getObscuritySummary(String lastfmid) {
        try (Connection connection = dataSource.getConnection()) {
            return queriesDao.getUserObscuritPoints(connection, lastfmid);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    public void truncateRandomPool() {
        try (Connection connection = dataSource.getConnection()) {
            updaterDao.truncateRandomPool(connection);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public List<GlobalCrown> getGlobalArtistRanking(Long artistId, boolean includeBottedUsers, long ownerId) {
        try (Connection connection = dataSource.getConnection()) {
            return queriesDao.getGlobalKnows(connection, artistId, includeBottedUsers, ownerId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public UniqueWrapper<ArtistPlays> getGlobalUniques(String lastfmid) {
        try (Connection connection = dataSource.getConnection()) {
            return queriesDao.getGlobalUniques(connection, lastfmid);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    public UniqueWrapper<ArtistPlays> getGlobalCrowns(String lastfmid, int threshold, boolean includeBottedUsers, long ownerId) {
        try (Connection connection = dataSource.getConnection()) {
            return queriesDao.getGlobalCrowns(connection, lastfmid, threshold, includeBottedUsers, ownerId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    public void createGuild(long guildId) {
        try (Connection connection = dataSource.getConnection()) {
            userGuildDao.createGuild(connection, guildId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    public long findByNameConsequent(String artistName) {
        try (Connection connection = dataSource.getConnection()) {
            try {
                return updaterDao.getArtistId(connection, artistName);
            } catch (InstanceNotFoundException e) {
                ScrobbledArtist scrobbledArtist = new ScrobbledArtist(artistName, 0, null);
                updaterDao.insertArtistSad(connection, (scrobbledArtist));
                return scrobbledArtist.getArtistId();
            }
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public UpdaterUserWrapper getUserUpdateStatus(long discordId) throws InstanceNotFoundException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return updaterDao.getUserUpdateStatus(connection, discordId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public ResultWrapper<ArtistPlays> getArtistsFrequencies(long guildID) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.getArtistsFrequencies(connection, guildID);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public long getArtistFrequencies(long guildID, long artistId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.getArtistFrequencies(connection, guildID, artistId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public long getGlobalArtistFrequencies(long artistId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.getArtistFrequencies(connection, null, artistId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public ResultWrapper<ArtistPlays> getArtistsFrequenciesGlobal() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.getGlobalArtistFrequencies((connection));
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public long getGlobalArtistPlays(long artistId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.getArtistPlays(connection, null, artistId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public long getServerArtistPlays(long guildID, long artistId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.getArtistPlays(connection, guildID, artistId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }


    public ResultWrapper<ArtistPlays> getServerArtistsPlays(long guildID) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.getArtistPlayCount(connection, guildID);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public ResultWrapper<ArtistPlays> getArtistPlayCountGlobal() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.getGlobalArtistPlayCount((connection));
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public List<ScrobbledArtist> getAllUserArtist(long discordId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.getAllUsersArtist(connection, discordId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void changeLastFMName(long userId, String lastFmID) throws DuplicateInstanceException, InstanceNotFoundException {
        try (Connection connection = dataSource.getConnection()) {
            try {
                LastFMData lastFMData = userGuildDao.findByLastFMId(connection, lastFmID);
                throw new DuplicateInstanceException(lastFmID);
            } catch (InstanceNotFoundException ignored) {
            }
            updaterDao.deleteAllArtists(connection, lastFmID);
            LastFMData lastFmData = userGuildDao.findLastFmData(connection, userId);
            lastFmData.setName(lastFmID);
            userGuildDao.updateLastFmData(connection, lastFmData);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    public List<LbEntry> matchingArtistsCount(String lastFmId, long guildId, int extraParam) {
        try (Connection connection = dataSource.getConnection()) {
            affinityDao.setServerTempTable(connection, guildId, lastFmId, extraParam);
            List<LbEntry> matchingCount = affinityDao.getMatchingCount(connection);
            matchingCount.sort(Comparator.comparingInt(LbEntry::getEntryCount).reversed());
            affinityDao.cleanUp(connection, true);
            return matchingCount;
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public List<ArtistLbGlobalEntry> globalMatchings(String lastFmId, Long guildId, int theshold) {
        try (Connection connection = dataSource.getConnection()) {
            affinityDao.setGlobalTable(connection, lastFmId, theshold);
            List<ArtistLbGlobalEntry> matchingCount = affinityDao.getGlobalMatchingCount(connection);
            matchingCount.sort(Comparator.comparingInt(LbEntry::getEntryCount).reversed());
            affinityDao.cleanUpGlobal(connection, true);
            return matchingCount;
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void addAlias(String alias, long toArtist) throws DuplicateInstanceException {
        try (Connection connection = dataSource.getConnection()) {
            updaterDao.addAlias(connection, alias, toArtist);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void enqueAlias(String alias, long toArtist, long whom) {
        try (Connection connection = dataSource.getConnection()) {
            updaterDao.queueAlias(connection, alias, toArtist, whom);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public AliasEntity getNextInAliasQueue() {
        try (Connection connection = dataSource.getConnection()) {
            return updaterDao.getNextInAliasQueue(connection);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void deleteAliasById(long aliasId) throws InstanceNotFoundException {
        try (Connection connection = dataSource.getConnection()) {
            updaterDao.deleteAliasById(connection, aliasId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public long getArtistId(String artist) throws InstanceNotFoundException {
        try (Connection connection = dataSource.getConnection()) {
            return updaterDao.getArtistId(connection, artist);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public OptionalLong checkArtistUrlExists(long artistId, String urlParsed) {
        try (Connection connection = dataSource.getConnection()) {
            return updaterDao.checkArtistUrlExists(connection, artistId, urlParsed);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public OptionalLong checkQueuedUrlExists(long artistId, String urlParsed) {
        try (Connection connection = dataSource.getConnection()) {
            return updaterDao.checkQueuedUrlExists(connection, artistId, urlParsed);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }


    public VoteStatus castVote(long urlId, long discordId, boolean isPositive) {
        try (Connection connection = dataSource.getConnection()) {
            Boolean hasVotedBefore = queriesDao.hasUserVotedImage(connection, urlId, discordId);
            updaterDao.castVote(connection, urlId, discordId, isPositive);
            if (hasVotedBefore == null) {
                return VoteStatus.NEW_VOTE;
            }
            return hasVotedBefore == isPositive ? VoteStatus.SAME_VALUE : VoteStatus.CHANGE_VALUE;
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public List<VotingEntity> getAllArtistImages(long artist_id) {

        try (Connection connection = dataSource.getConnection()) {
            return queriesDao.getAllArtistImages(connection, artist_id);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void report(long urlId, long userIdLong) {
        try (Connection connection = dataSource.getConnection()) {
            updaterDao.reportImage(connection, urlId, userIdLong);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public List<String> getArtistAliases(long artistId) {
        try (Connection connection = dataSource.getConnection()) {
            return queriesDao.getArtistAliases(connection, artistId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void upsertArtistSad(ScrobbledArtist scrobbledArtist) {
        try (Connection connection = dataSource.getConnection()) {
            updaterDao.insertArtistSad(connection, scrobbledArtist);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }


    public void removeReportedImage(long alt_id, long image_owner, long mod_id) {
        try (Connection connection = dataSource.getConnection()) {
            updaterDao.removeImage(connection, alt_id);
            updaterDao.logRemovedImage(connection, image_owner, mod_id);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public int getReportCount() {
        try (Connection connection = dataSource.getConnection()) {
            return updaterDao.getReportCount(connection);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public int getQueueUrlCount() {
        try (Connection connection = dataSource.getConnection()) {
            return updaterDao.getQueueUrlCount(connection);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void ignoreReportedImage(long altId) {
        try (Connection connection = dataSource.getConnection()) {
            updaterDao.removeReport(connection, altId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void rejectQueuedImage(long queuedId) {
        try (Connection connection = dataSource.getConnection()) {
            updaterDao.removeQueuedImage(connection, queuedId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public ReportEntity getNextReport(LocalDateTime localDateTime, Set<Long> skippedIds) {
        try (Connection connection = dataSource.getConnection()) {
            return updaterDao.getReportEntity(connection, localDateTime, skippedIds);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    public ImageQueue getNextQueue(LocalDateTime localDateTime, Set<Long> skippedIds) {
        try (Connection connection = dataSource.getConnection()) {
            return updaterDao.getUrlQueue(connection, localDateTime, skippedIds);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    public Affinity getAffinity(String ogLastFmID, String receiverLastfmID, int threshold) {
        try (Connection connection = dataSource.getConnection()) {
            affinityDao.initTempTable(connection, ogLastFmID, receiverLastfmID, threshold);
            Affinity affinity = affinityDao.getPercentageStats(connection, ogLastFmID, receiverLastfmID, threshold);
            String[] recs = affinityDao.doRecommendations(connection, ogLastFmID, receiverLastfmID);
            affinity.setReceivingRec(recs[0]);
            affinity.setOgRec(recs[1]);
            ResultWrapper<UserArtistComparison> similar = queriesDao.similar(connection, List.of(ogLastFmID, receiverLastfmID), 5);
            affinity.addMatchings(similar.getResultList());
            affinityDao.cleanUp(connection, false);
            return affinity;
        } catch (SQLException e) {
            throw new ChuuServiceException(e);

        }

    }

    public List<Affinity> getServerAffinity(String ogLastFmID, long guildId, int threshold) {
        try (Connection connection = dataSource.getConnection()) {
            affinityDao.setServerTempTable(connection, guildId, ogLastFmID, threshold);
            List<Affinity> affinityList = affinityDao.doServerAffinity(connection, ogLastFmID, threshold);
            affinityDao.cleanUp(connection, true);
            return affinityList;
        } catch (SQLException e) {
            throw new ChuuServiceException(e);

        }
    }

    public List<GlobalAffinity> getGlobalAffinity(String ogLastFmID, Long guildId, int threshold) {
        try (Connection connection = dataSource.getConnection()) {
            affinityDao.setGlobalTable(connection, ogLastFmID, threshold);
            List<GlobalAffinity> affinityList = affinityDao.doGlobalAffinity(connection, ogLastFmID, threshold);
            affinityDao.cleanUpGlobal(connection, true);
            return affinityList;
        } catch (SQLException e) {
            throw new ChuuServiceException(e);

        }
    }

    public List<ScrobbledArtist> getRecommendation(long giverDiscordId, long receiverDiscordId, boolean ignorePast, int limit) {
        try (Connection connection = dataSource.getConnection()) {
            return queriesDao.getRecommendations(connection, giverDiscordId, receiverDiscordId, ignorePast, limit);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);

        }
    }

    public void insertRecommendation(long secondDiscordID, long firstDiscordID, long artistId) {
        try (Connection connection = dataSource.getConnection()) {
            updaterDao.insertPastRecommendation(connection, secondDiscordID, firstDiscordID, artistId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);

        }

    }

    public void updateGuildCrownThreshold(long guildId, int newThreshold) {
        try (Connection connection = dataSource.getConnection()) {
            updaterDao.updateGuildCrownThreshold(connection, guildId, newThreshold);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }


    public List<LbEntry> getScrobblesLeaderboard(long guildId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.getScrobblesLeaderboard(connection, guildId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public List<CrownableArtist> getCrownable(Long discordId, Long guildId, boolean skipCrownws, boolean onlySecond, int crownDistance) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.getCrownable(connection, discordId, guildId, skipCrownws, onlySecond, crownDistance);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public Map<Long, Float> getRateLimited() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return queriesDao.getRateLimited(connection);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void removeRateLimit(long discordId) {
        try (Connection connection = dataSource.getConnection()) {
            userGuildDao.removeRateLimit(connection, discordId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void addRateLimit(long discordId, float queries_second) {
        try (Connection connection = dataSource.getConnection()) {
            userGuildDao.upsertRateLimit(connection, discordId, queries_second);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void setPrivateUpdate(long discordId, boolean privateUpdate) {
        try (Connection connection = dataSource.getConnection()) {
            userGuildDao.setUserProperty(connection, discordId, "private_update", privateUpdate);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void setImageNotify(long discordId, boolean imageNotify) {
        try (Connection connection = dataSource.getConnection()) {
            userGuildDao.setUserProperty(connection, discordId, "notify_image", imageNotify);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void setChartEmbed(long discordId, @NotNull ChartMode chartMode) {
        try (Connection connection = dataSource.getConnection()) {
            userGuildDao.setUserProperty(connection, discordId, "chart_mode", chartMode);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void insertServerDisabled(long discordId, String commandName) {
        try (Connection connection = dataSource.getConnection()) {
            this.userGuildDao.insertServerDisabled(connection, discordId, commandName);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void insertChannelCommandStatus(long discordId, long channelId, String commandName, boolean enabled) {
        try (Connection connection = dataSource.getConnection()) {
            this.userGuildDao.insertChannelCommandStatus(connection, discordId, channelId, commandName, enabled);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void deleteServerCommandStatus(long discordId, String commandName) {
        try (Connection connection = dataSource.getConnection()) {
            this.userGuildDao.deleteServerCommandStatus(connection, discordId, commandName);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void deleteChannelCommandStatus(long discordId, long channelId, String commandName) {
        try (Connection connection = dataSource.getConnection()) {
            this.userGuildDao.deleteChannelCommandStatus(connection, discordId, channelId, commandName);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public MultiValuedMap<Long, String> initServerCommandStatuses() {
        try (Connection connection = dataSource.getConnection()) {
            return this.userGuildDao.initServerCommandStatuses(connection);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public MultiValuedMap<Pair<Long, Long>, String> initServerChannelsCommandStatuses(boolean enabled) {
        try (Connection connection = dataSource.getConnection()) {
            return this.userGuildDao.initServerChannelsCommandStatuses(connection, enabled);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    public void acceptImageQueue(long queuedId, String url, long artistId, long discordId) {
        try (Connection connection = dataSource.getConnection()) {
            updaterDao.removeQueuedImage(connection, queuedId);
            long urlId = updaterDao.upsertUrl(connection, url, artistId, discordId);
            updaterDao.castVote(connection, urlId, discordId, true);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    public GuildProperties getGuildProperties(long guildId) throws InstanceNotFoundException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return userGuildDao.getGuild(connection, guildId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    public void setWhoknowsMode(long discordId, @NotNull WhoKnowsMode whoKnowsMode) {
        try (Connection connection = dataSource.getConnection()) {
            userGuildDao.setUserProperty(connection, discordId, "whoknows_mode", whoKnowsMode);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    public void setRemainingImagesMode(long discordId, @NotNull RemainingImagesMode remainingImagesMode) {
        try (Connection connection = dataSource.getConnection()) {
            userGuildDao.setUserProperty(connection, discordId, "remaining_mode", remainingImagesMode);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    public void setPrivacyMode(long discordId, @NotNull PrivacyMode privacyMode) {
        try (Connection connection = dataSource.getConnection()) {
            userGuildDao.setUserProperty(connection, discordId, "privacy_mode", privacyMode);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    public void setRemainingImagesModeServer(long guildId, @Nullable RemainingImagesMode remainingImagesMode) {
        try (Connection connection = dataSource.getConnection()) {
            userGuildDao.setGuildProperty(connection, guildId, "remaining_mode", remainingImagesMode);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void setServerWhoknowMode(long guildId, @Nullable WhoKnowsMode images) {
        try (Connection connection = dataSource.getConnection()) {
            userGuildDao.setGuildProperty(connection, guildId, "whoknows_mode", images);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void setServerChartMode(long guildId, @Nullable ChartMode chartMode) {
        try (Connection connection = dataSource.getConnection()) {
            userGuildDao.setGuildProperty(connection, guildId, "chart_mode", chartMode);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void setChartDefaults(int x, int y, long discordId) {
        try (Connection connection = dataSource.getConnection()) {
            userGuildDao.setChartDefaults(connection, discordId, x, y);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public void insertRatings(long userId, List<RYMImportRating> ratings) {
        try (Connection connection = dataSource.getConnection()) {


            /* Prepare connection. */

            connection.setAutoCommit(false);
            if (!ratings.isEmpty()) {
                //connection.prepareStatement("set foreign_key_checks  = 0").execute();
                rymDao.cleanUp(connection);
                //delete everything first to have a clean start
                updaterDao.deleteAllRatings(connection, userId);
                /* Do work. */
                updaterDao.fillALbumsByRYMID(connection, ratings);
                Map<Boolean, List<RYMImportRating>> map = ratings.stream().collect(Collectors.partitioningBy(albumRating -> albumRating.getId() == -1L));
                List<RYMImportRating> knownAlbums = map.get(false);
                List<RYMImportRating> unknownAlbums = map.get(true);

                rymDao.setServerTempTable(connection, unknownAlbums);
                //Returns a map with rym_id -> artist_id


                Map<Long, Long> artists = rymDao.findArtists(connection);

                Map<Boolean, List<RYMImportRating>> map1 = unknownAlbums.stream().collect(Collectors.partitioningBy(x -> {
                    Long aLong = artists.get(x.getRYMid());
                    return aLong != null && aLong > 0;
                }));

                // List of artist that were found in last step
                List<RYMImportRating> ratingsWithKnownArtist = map1.get(true);
                //We set the found id
                for (RYMImportRating RYMImportRating : ratingsWithKnownArtist) {
                    Long aLong = artists.get(RYMImportRating.getRYMid());
                    RYMImportRating.setArtist_id(aLong);
                }
                //This were not found
                List<RYMImportRating> ratingsWithUnknownArtist = map1.get(false);

                //This were the ids that were found, we reduce a bit the size of the table
                Collection<Long> rymIdsToDelete = artists.keySet();
                rymDao.deletePartialTempTable(connection, Set.copyOf(rymIdsToDelete));

                //Over the remaining items we do an auxiliar search
                Map<Long, Long> artistsAuxiliar = rymDao.findArtistsAuxiliar(connection);
                map1 = ratingsWithUnknownArtist.stream().collect(Collectors.partitioningBy(x -> {
                    Long aLong = artistsAuxiliar.get(x.getRYMid());
                    return aLong != null && aLong > 0;
                }));

                //These were found on the auxiliar search
                List<RYMImportRating> auxiliarFoundArtists = map1.get(true);
                List<RYMImportRating> notFoundAuxiiliar = map1.get(false);
                for (RYMImportRating RYMImportRating : auxiliarFoundArtists) {
                    Long aLong = artistsAuxiliar.get(RYMImportRating.getRYMid());
                    RYMImportRating.setArtist_id(aLong);
                }
                ratingsWithUnknownArtist.addAll(notFoundAuxiiliar);
                ratingsWithKnownArtist.addAll(auxiliarFoundArtists);


                for (RYMImportRating RYMImportRating : ratingsWithUnknownArtist) {
                    ScrobbledArtist scrobbledArtist = new ScrobbledArtist(RYMImportRating.getFirstName() + " " + RYMImportRating.getLastName(), 0, null);
                    updaterDao.insertArtistSad(connection, scrobbledArtist);
                    RYMImportRating.setArtist_id(scrobbledArtist.getArtistId());
                }
                //KnownAlbumvs vs Ratings WithKnownArtist
                // Now we have on ratingsw with known artists all ratings with unknown album
                ratingsWithKnownArtist.addAll(ratingsWithUnknownArtist);

                for (RYMImportRating RYMImportRating : ratingsWithKnownArtist) {
                    updaterDao.insertAlbumSad(connection, RYMImportRating);
                }

                knownAlbums.addAll(ratingsWithKnownArtist);
                knownAlbums = knownAlbums.stream()
                        .collect(Collectors.groupingBy(RYMImportRating::getId, Collectors.toList())).entrySet().stream()
                        // Dont think is equivalent :thinking:
                        .map(rymImportRatings -> rymImportRatings.getValue().stream().max(Comparator.comparingInt(RYMImportRating::getRating)).orElse(null))
                        .filter(Objects::nonNull).collect(Collectors.toList());

                Savepoint savepoint = connection.setSavepoint();
                try {
                    rymDao.insertRatings(connection, knownAlbums, userId);
                    connection.commit();
                } catch (SQLTransactionRollbackException exception) {
                    connection.rollback(savepoint);
                    rymDao.insertRatings(connection, knownAlbums, userId);
                    connection.commit();
                }
                //connection.rollback();

            }
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    public AlbumRatings getRatingsByName(long idLong, String album, long artistId) {

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return rymDao.getRatingsByName(connection, idLong, album, artistId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public Collection<AlbumRatings> getArtistRatings(long artistId, long guildId) {

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return rymDao.getArtistRatings(connection, guildId, artistId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public List<ScoredAlbumRatings> getGlobalTopRatings() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return rymDao.getGlobalTopRatings(connection);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public List<ScoredAlbumRatings> getServerTopRatings() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return rymDao.getGlobalTopRatings(connection);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public List<ScoredAlbumRatings> getSelfRatingsScore(long discordId, Short ratingNumber) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return rymDao.getSelfRatingsScore(connection, ratingNumber, discordId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public RymStats getUserRymStatms(long discordId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return rymDao.getUserRymStatms(connection, discordId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public RymStats getRYMServerStats(long guildId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return rymDao.getServerStats(connection, guildId);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    public RymStats getRYMBotStats() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return rymDao.getRYMBotStats(connection);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }
}
