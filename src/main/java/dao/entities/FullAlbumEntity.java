package dao.entities;

import java.util.ArrayList;
import java.util.List;

public class FullAlbumEntity {
	private final String artist;
	private final String album;
	private final int totalPlayNumber;
	private final String albumUrl;
	private final List<Track> trackList = new ArrayList<>();

	private final String username;
	private String artistUrl;

	public FullAlbumEntity(String artist, String album, int totalPlayNumber, String albumUrl, String username) {
		this.artist = artist;
		this.album = album;
		this.totalPlayNumber = totalPlayNumber;
		this.albumUrl = albumUrl;
		this.username = username;
	}

	public String getUsername() {
		return username;
	}


	public String getArtistUrl() {
		return artistUrl;
	}

	public void setArtistUrl(String artistUrl) {
		this.artistUrl = artistUrl;
	}

	public String getAlbumUrl() {
		return albumUrl;
	}

	public void addTrack(Track track) {
		trackList.add(track);
	}

	public String getArtist() {
		return artist;
	}

	public String getAlbum() {
		return album;
	}

	public int getTotalPlayNumber() {
		return totalPlayNumber;
	}

	public List<Track> getTrackList() {
		return trackList;
	}
}