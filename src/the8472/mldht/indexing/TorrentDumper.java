package the8472.mldht.indexing;

import static java.lang.Math.min;
import static the8472.utils.Functional.typedGet;

import the8472.bencode.BDecoder;
import the8472.bencode.BEncoder;
import the8472.bt.TorrentUtils;
import the8472.bt.UselessPeerFilter;
import the8472.mldht.Component;
import the8472.mldht.TorrentFetcher;
import the8472.mldht.TorrentFetcher.FetchTask;
import the8472.mldht.indexing.TorrentDumper.FetchStats.State;
import the8472.utils.ConfigReader;
import the8472.utils.concurrent.LoggingScheduledThreadPoolExecutor;
import the8472.utils.io.FileIO;

import lbms.plugins.mldht.kad.DHT;
import lbms.plugins.mldht.kad.Key;
import lbms.plugins.mldht.kad.RPCServer;
import lbms.plugins.mldht.kad.DHT.LogLevel;
import lbms.plugins.mldht.kad.messages.AnnounceRequest;
import lbms.plugins.mldht.kad.messages.GetPeersRequest;
import lbms.plugins.mldht.kad.messages.MessageBase;
import lbms.plugins.mldht.kad.utils.ThreadLocalUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class TorrentDumper implements Component {
	
	Collection<DHT> dhts;
	Path storageDir = Paths.get(".", "dump-storage");
	Path statsDir = storageDir.resolve("stats");
	Path torrentDir = storageDir.resolve("torrents");
	
	ScheduledThreadPoolExecutor scheduler;
	
	ConcurrentSkipListMap<Key, FetchStats> fromMessages;
	ConcurrentMap<InetAddress, Long> blocklist = new ConcurrentHashMap<>();
	
	TorrentFetcher fetcher;
	UselessPeerFilter pf;
	
	static class FetchStats {
		final Key k;
		int insertCount = 1;
		InetAddress lastTouchedBy;
		long creationTime = -1;
		long lastFetchTime = -1;
		State state = State.INITIAL;
		
		enum State {
			INITIAL,
			PRIORITY,
			FAILED;
			
			public Path stateDir(Path statsdir) {
				return statsdir.resolve(name().toLowerCase());
			}
			
			
		}

		public FetchStats(Key k, Consumer<FetchStats> init) {
			Objects.requireNonNull(k);
			this.k = k;
			if(init != null)
				init.accept(this);
		}

		static FetchStats fromBencoded(Map<String, Object> map) {
			Key k = typedGet(map, "k", byte[].class).map(Key::new).orElseThrow(() -> new IllegalArgumentException("missing key in serialized form"));
			
			return new FetchStats(k, fs -> {
				typedGet(map, "addr", byte[].class).map(t -> {
					try {
						return InetAddress.getByAddress(t);
					} catch (UnknownHostException e) {
						return null;
					}
				}).ifPresent(addr -> fs.lastTouchedBy = addr);
				
				typedGet(map, "state", byte[].class).map(b -> new String(b, StandardCharsets.ISO_8859_1)).map(str -> {
					try {
						return State.valueOf(str);
					} catch (IllegalArgumentException e) {
						return null;
					}
				}).ifPresent(st -> fs.state = st);
				
				typedGet(map, "created", Long.class).ifPresent(time -> fs.creationTime = time);
				typedGet(map, "cnt", Long.class).ifPresent(cnt -> fs.insertCount = cnt.intValue());
				typedGet(map, "fetchtime", Long.class).ifPresent(time -> fs.lastFetchTime = time);
				
			});
		}
		
		Map<String, Object> forBencoding() {
			Map<String, Object> map = new TreeMap<>();
			
			map.put("k", k.getHash());
			map.put("cnt", insertCount);
			map.put("addr", lastTouchedBy.getAddress());
			map.put("created", creationTime);
			map.put("state", state.name());
			map.put("fetchtime", lastFetchTime);
			
			return map;
		}

		public Key getK() {
			return k;
		}
		
		public FetchStats merge(FetchStats other) {
			if(!k.equals(other.k))
				throw new IllegalArgumentException("key mismatch");
			
			boolean otherIsNewer = other.creationTime > creationTime;
			
			insertCount += other.insertCount;
			lastTouchedBy = otherIsNewer ? other.lastTouchedBy : lastTouchedBy;
			creationTime = min(creationTime, other.creationTime);
			
			return this;
		}
		
		public void setState(State newState) {
			state = newState;
		}
		
		public Path name(Path dir, String suffix) {
			String hex = k.toString(false);
			return dir.resolve(hex.substring(0, 2)).resolve(hex.substring(2, 4)).resolve(hex+suffix);
		}
		
		public Path statsName(Path statsDir, State st) {
			if(st == null)
				st = state;
			return name(st.stateDir(statsDir), ".stats");
			
		}
		
		
	}

	@Override
	public void start(Collection<DHT> dhts, ConfigReader config) {
		this.dhts = dhts;
		fromMessages = new ConcurrentSkipListMap<>();
		scheduler = new LoggingScheduledThreadPoolExecutor(2, new LoggingScheduledThreadPoolExecutor.NamedDaemonThreadFactory("torrent dumper"), this::log);
		
		fetcher = new TorrentFetcher(dhts);
		
		fetcher.setMaxOpen(40);

		dhts.forEach(d -> d.addIncomingMessageListener(this::incomingMessage));
		try {
			pf = new UselessPeerFilter(storageDir.resolve("bad-peers"));
			Files.createDirectories(torrentDir);
			for(State st : FetchStats.State.values()) {
				Files.createDirectories(st.stateDir(statsDir));
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		
		fetcher.setPeerFilter(pf);
		
		scheduler.scheduleWithFixedDelay(this::dumpStats, 10, 1, TimeUnit.SECONDS);
		scheduler.scheduleWithFixedDelay(this::startFetches, 10, 1, TimeUnit.SECONDS);
		scheduler.scheduleWithFixedDelay(this::cleanBlocklist, 1, 1, TimeUnit.MINUTES);
		scheduler.scheduleWithFixedDelay(this::diagnostics, 30, 30, TimeUnit.SECONDS);
		scheduler.scheduleWithFixedDelay(this::purgeStats, 5, 15, TimeUnit.MINUTES);
		scheduler.scheduleWithFixedDelay(this::scrubActive, 10, 20, TimeUnit.SECONDS);
		scheduler.scheduleWithFixedDelay(() -> {
			try {
				pf.clean();
			} catch (IOException e) {
				log(e);
			}
		}, 10, 5, TimeUnit.MINUTES);
	}
	
	void log(Throwable t) {
		DHT.log(t, LogLevel.Error);
	}
	
	void cleanBlocklist() {
		long now = System.currentTimeMillis();
		blocklist.entrySet().removeIf(e -> {
			return (now - e.getValue()) > TimeUnit.MINUTES.toMillis(10);
		});
		
	}
	
	void incomingMessage(DHT d, MessageBase m) {
		if(d.getMismatchDetector().isIdInconsistencyExpected(m.getOrigin(), m.getID()))
			return;
		
		if(m instanceof GetPeersRequest) {
			GetPeersRequest gpr = (GetPeersRequest) m;
			
			RPCServer srv = m.getServer();
			
			Key theirID = gpr.getID();
			
			if(d.getNode().isLocalId(theirID))
				return;
			
			Key ourId = srv.getDerivedID();
			Key target = gpr.getInfoHash();

			if(Stream.of(theirID, ourId, target).distinct().count() != 3)
				return;

			int myCloseness = ourId.distance(target).leadingOneBit();
			int theirCloseness = theirID.distance(target).leadingOneBit();
			
			
			if(theirCloseness > myCloseness && theirCloseness - myCloseness >= 8)
				return; // they're looking for something that's significantly closer to their own ID than we are
			process(gpr.getInfoHash(), gpr.getOrigin().getAddress(), null);
		}
		if(m instanceof AnnounceRequest) {
			AnnounceRequest anr = (AnnounceRequest) m;
			process(anr.getInfoHash(), anr.getOrigin().getAddress(), anr.getNameUTF8().orElse(null));
		}
	}
	
	void process(Key k, InetAddress src, String name) {
		
		
		fromMessages.compute(k, (unused, f) -> {
			FetchStats f2 = new FetchStats(k, init -> {
				init.lastTouchedBy = src;
				init.insertCount = 1;
				init.creationTime = System.currentTimeMillis();
			});
			return f == null ? f2 : f.merge(f2);
		});
						
	}
	
	Key cursor = Key.MIN_KEY;
	
	void dumpStats() {
		long now = System.currentTimeMillis();
		
		for(;;) {
			Entry<Key, FetchStats> entry = fromMessages.ceilingEntry(cursor);
			if(entry == null) {
				cursor = Key.MIN_KEY;
				break;
			}
			
			Key k = entry.getKey();
			FetchStats s = entry.getValue();
			
			fromMessages.remove(k);
			
			cursor = k.add(Key.setBit(159));

			
			if(Files.exists(s.name(torrentDir, ".torrent"))) {
				continue;
			}
			


			
			try {
				
				Optional<Path> existing = Stream.of(s.statsName(statsDir, FetchStats.State.FAILED), s.statsName(statsDir, FetchStats.State.PRIORITY), s.statsName(statsDir, FetchStats.State.INITIAL)).filter(Files::isRegularFile).findFirst();

				if(!existing.isPresent()) {
					// only throttle IPs for new hashes we don't already know about and wouldn't try anyway
					if(activeCount.get() > 50 && blocklist.putIfAbsent(s.lastTouchedBy, now) != null)
						continue;
				}
				
				if(existing.isPresent()) {
					Path p = existing.get();
					try {
						FetchStats old = FetchStats.fromBencoded(new BDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(p))));
						
						// avoid double-taps
						if(old.lastTouchedBy.equals(s.lastTouchedBy))
							return;
						
						s.merge(old);
						
						if(old.state != FetchStats.State.INITIAL)
							s.state = old.state;
						
					} catch (IOException e) {
						log(e);
					}
				}
				
				if(s.state == State.INITIAL && s.insertCount > 1) {
					s.state = State.PRIORITY;
					if(existing.isPresent())
						Files.deleteIfExists(existing.get());
				}
					
				
				Path statsFile = s.statsName(statsDir, null);
				
				Path tempFile = Files.createTempFile(statsDir, statsFile.getFileName().toString(), ".stats");
				
				try(FileChannel ch = FileChannel.open(tempFile, StandardOpenOption.WRITE)) {
					ByteBuffer buf = new BEncoder().encode(s.forBencoding(), 16*1024);
					ch.write(buf);
					ch.close();
					Files.createDirectories(statsFile.getParent());
					Files.move(tempFile, statsFile, StandardCopyOption.ATOMIC_MOVE);
				} finally {
					Files.deleteIfExists(tempFile);
				}

				
			} catch (Exception e) {
				log(e);
			}
			
			
			
		}
				
	}
	
	void purgeStats() {
		Path failedDir = FetchStats.State.FAILED.stateDir(statsDir);
		
		long now = System.currentTimeMillis();
		
		try (Stream<Path> pst = Files.find(failedDir, 5, (p, a) -> a.isRegularFile())) {
			
			Stream<FetchStats> st = filesToFetchers(pst);
			
			st.filter(Objects::nonNull).filter(stat -> now - stat.lastFetchTime > TimeUnit.HOURS.toMillis(2)).forEach(stat -> {
				try {
					Files.deleteIfExists(stat.statsName(statsDir, null));
				} catch (IOException e) {
					log(e);
				}
			});

		} catch (UncheckedIOException | IOException e) {
			log(e);
		}

		// 0 -> stats, 1 -> {failed|initial|prio}, 2 -> 00, 3 -> 00/00
		try (Stream<Path> st = Files.find(statsDir, 3, (p, attr) -> attr.isDirectory())) {
			st.filter(d -> {
				try (DirectoryStream<Path> dst = Files.newDirectoryStream(d)) {
					return !dst.iterator().hasNext();
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}).forEach(d -> {
				try {
					Files.deleteIfExists(d);
				} catch(DirectoryNotEmptyException e) {
					// someone on another thread wrote to it. do nothing
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		} catch (UncheckedIOException | IOException e) {
			log(e);
		}

			
		
		
	}
	
	
	Stream<Path> dirShuffler(Path p) {
		if(!Files.isDirectory(p))
			return null;
		List<Path> sub;
		try(Stream<Path> st = Files.list(p)) {
			sub = st.collect(Collectors.toList());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		Collections.shuffle(sub);
		return sub.stream();
	}
	
	
	Stream<Path> fetchStatsStream(Stream<Path> rootDirs) throws IOException {
		
		
		


		// this does not use a true shuffle, the stream will emit some clusters at the 8bit keyspace granularity
		// it's closer to linear scan from a random starting point
		// but polling in small batches should lead to reasonable task randomization without expensive full directory traversal
		Stream<Path> leafs = rootDirs.flatMap(d -> {
			return Stream.of(d).flatMap(this::dirShuffler).flatMap(this::dirShuffler).flatMap(this::dirShuffler);
		});

		
		return leafs;
	}
	
	Stream<FetchStats> filesToFetchers(Stream<Path> st) throws IOException {
		ThreadLocal<ByteBuffer> bufProvider = new ThreadLocal<>();
		
		return st.map(p -> {
			try(FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
				long size = ch.size();
				ByteBuffer buf = bufProvider.get();
				if(buf == null || buf.capacity() < size)
					buf = ByteBuffer.allocate((int) (size * 1.5));
				buf.clear();
				ch.read(buf);
				buf.flip();
				bufProvider.set(buf);
				return FetchStats.fromBencoded(ThreadLocalUtils.getDecoder().decode(buf));
			} catch(NoSuchFileException ex) {
				// expect async deletes
				return null;
			} catch(IOException ex) {
				log(ex);
				return null;
			}
		}).filter(Objects::nonNull);
		
	}
	
	
	void startFetches() {
		try {
			Path prio = FetchStats.State.PRIORITY.stateDir(statsDir);
			Path normal = FetchStats.State.INITIAL.stateDir(statsDir);
			try(Stream<FetchStats> st = filesToFetchers(fetchStatsStream(Stream.of(prio, normal)))) {
				st.limit(200).forEachOrdered(this::fetch);
			};
		} catch (Exception e) {
			log(e);
		}
		
	}
	
	AtomicInteger activeCount = new AtomicInteger();
	ConcurrentHashMap<Key, FetchTask> activeTasks = new ConcurrentHashMap<>();
	
	void scrubActive() {
		
		// as long as there are young connections it means some fraction of the fetch tasks dies quickly
		// we're fine with other ones taking longer as long as that's the case
		long youngConnections = activeTasks.values().stream().filter(t -> t.attemptedCount() < 5).count();
		
		if(youngConnections > 15 || activeCount.get() < 90)
			return;
		
		
		Comparator<Map.Entry<FetchTask, Integer>> comp = Map.Entry.comparingByValue();
		comp = comp.reversed();
		
		activeTasks.values().stream().map(t -> new AbstractMap.SimpleEntry<>(t, t.attemptedCount())).filter(e -> e.getValue() > 70).sorted(comp).limit(10).forEachOrdered(e -> {
			e.getKey().stop();
		});
	}
	
	void fetch(FetchStats stats) {
		Key k = stats.getK();
		
		if(activeTasks.containsKey(k))
			return;
		
		if(activeCount.get() > 100)
			return;
		
		FetchTask t = fetcher.fetch(k, (fetch) -> {
			fetch.configureLookup(lookup -> {
				lookup.setFastTerminate(true);
				lookup.setLowPriority(true);
			});
		});
		
		activeCount.incrementAndGet();
		activeTasks.put(k, t);
		
		t.awaitCompletion().thenRun(() -> {
			scheduler.execute(() -> {
				// run on the scheduler so we don't end up with interfering file ops
				taskFinished(stats, t);
			});
			
		});
	}
	
	void taskFinished(FetchStats stats, FetchTask t) {
		activeCount.decrementAndGet();
		blocklist.remove(stats.lastTouchedBy);
		activeTasks.remove(t.infohash());
		try {
			for(FetchStats.State st : FetchStats.State.values()) {
				Files.deleteIfExists(stats.statsName(statsDir, st));
			}
			
			if(!t.getResult().isPresent()) {
				stats.setState(FetchStats.State.FAILED);
				stats.lastFetchTime = System.currentTimeMillis();
				
				Path failedStatsFile = stats.statsName(statsDir, null);
				Files.createDirectories(failedStatsFile.getParent());
				
				try(FileChannel statsChan = FileChannel.open(failedStatsFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
					statsChan.write(new BEncoder().encode(stats.forBencoding(), 4*1024));
				}
				return;
			}
			ByteBuffer buf = t.getResult().get();
			
			Path torrentFile = stats.name(torrentDir, ".torrent");
			Files.createDirectories(torrentFile.getParent());
			
			try(FileChannel chan = FileChannel.open(torrentFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
				chan.write(TorrentUtils.wrapBareInfoDictionary(buf));
			}
		} catch (Exception e) {
			log(e);
		}
		
	}
	
	void diagnostics() {
		try {
			FileIO.writeAndAtomicMove(storageDir.resolve("dumper.log"), (p) -> {
				p.format("Fetcher:%n established: %d%n sockets: %d %n%n", fetcher.openConnections(), fetcher.socketcount());
				
				p.format("FetchTasks: %d %n", activeCount.get());
				activeTasks.values().forEach(ft -> {
					p.println(ft.toString());
				});
			});
		} catch (IOException e) {
			log(e);
		}
	}
	

	@Override
	public void stop() {
		scheduler.shutdown();
		activeTasks.values().forEach(FetchTask::stop);
	}

}
