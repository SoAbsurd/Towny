package com.palmergames.bukkit.towny.database.handler;

import com.palmergames.bukkit.towny.Towny;
import com.palmergames.bukkit.towny.TownyMessaging;
import com.palmergames.bukkit.towny.TownyUniverse;
import com.palmergames.bukkit.towny.database.handler.annotations.PostLoad;
import com.palmergames.bukkit.towny.database.handler.annotations.SavedEntity;
import com.palmergames.bukkit.towny.exceptions.AlreadyRegisteredException;
import com.palmergames.bukkit.towny.exceptions.TownyRuntimeException;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.database.Saveable;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.TownyWorld;
import com.palmergames.bukkit.towny.utils.ReflectionUtil;
import com.palmergames.util.FileMgmt;
import org.apache.commons.lang.Validate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;

public class FlatFileDatabaseHandler extends DatabaseHandler {
	
	private static final Map<Class<?>, File> fileDirectoryCache = new HashMap<>();
	private static final File relationshipDir = new File(Towny.getPlugin().getDataFolder() + "/data/relationship/");
	private final Map<Field, Map<Saveable, String>> postLoadFields = new HashMap<>();
	
	// Create files
	static {
		if (!relationshipDir.exists()) {
			boolean mkdirRes = relationshipDir.mkdirs();
			if (!mkdirRes) {
				throw new TownyRuntimeException("Required Directories could not be created.");
			}
		}
	}

	@Override
	public void save(@NotNull Saveable obj) {
		// Validation/fail-fast safety
		Validate.notNull(obj);
		
		HashMap<String, String> saveMap = new HashMap<>();

		// Get field data.
		convertMapData(ReflectionUtil.getObjectMap(obj), saveMap);
		
		// Add save getter data.
		convertMapData(getSaveGetterData(obj), saveMap);
		
		File dir = getFlatFileDirectory(obj.getClass());
		
		// Make sure parent dir exists
		if (!dir.exists()) {
			if (!dir.mkdirs()) {
				throw new TownyRuntimeException("Could not make Directories for " + obj);
			}
		}
		
		// Save
		FileMgmt.mapToFile(saveMap, getFlatFile(obj.getClass(), obj.getUniqueIdentifier()));
	}

	@Override
	public boolean delete(@NotNull Saveable obj) {
		Validate.notNull(obj);
		
		File objFile = getFlatFile(obj.getClass(), obj.getUniqueIdentifier());
		
		if (objFile.exists()) {
			return objFile.delete();
		} else {
			TownyMessaging.sendErrorMsg("Cannot delete: " + objFile + ", it does not exist.");
			return false;
		}
		
	}
	
	@Nullable
	public <T extends Saveable> T load(@NotNull File file, @NotNull Class<T> clazz) {
		Validate.notNull(clazz);
		Validate.notNull(file);
		
		Constructor<T> objConstructor = null;
		try {
			objConstructor = clazz.getConstructor(UUID.class);
		} catch (NoSuchMethodException e) {
			e.printStackTrace();
		}

		T obj = null;
		try {
			Validate.isTrue(objConstructor != null);
			obj = objConstructor.newInstance((Object) null);
		} catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
			e.printStackTrace();
		}

		Validate.isTrue(obj != null);
		// Load all fields except the post load ones
		List<Field> fields = ReflectionUtil.getNonTransientFields(obj);

		HashMap<String, String> values = loadFileIntoHashMap(file);
		for (Field field : fields) {
			String fieldValue = values.get(field.getName());

			if (field.isAnnotationPresent(PostLoad.class)) {
				addToPostLoad(field, obj, fieldValue);
				continue;
			}
			
			loadField(obj, field, fieldValue);
		}
		
		return obj;
	}
	
	private void loadField(Saveable obj, Field field, String fieldValue) {
		Type type = field.getGenericType();
		Class<?> classType = field.getType();
		field.setAccessible(true);

		if (fieldValue == null) {
			return;
		}

//			// If there is a default value that already exists,
//			// make sure we are adapting to the same default value type.
//			try {
//				Object fieldValue = field.get(obj);
//
//				if (fieldValue != null) {
//					type = fieldValue.getClass();
//				}
//			} catch (IllegalAccessException e) {
//			}

		Object value;

		if (ReflectionUtil.isPrimitive(type)) {
			value = loadPrimitive(fieldValue, type);
		} else if (field.getType().isEnum()) {
			value = ReflectionUtil.loadEnum(fieldValue, classType);
		} else {
			value = fromStoredString(fieldValue, type);
		}

		if (value == null) {
			// ignore it as another already allocated value may be there.
			return;
		}

		try {
			field.set(obj, value);
		} catch (IllegalAccessException e) {
			throw new TownyRuntimeException(e.getMessage());
		}
	}
	
	private void addToPostLoad(Field field, Saveable object, String value) {
		Map<Saveable, String> objectValueMap = postLoadFields.computeIfAbsent(field,
												(f) -> new HashMap<>());
		
		objectValueMap.put(object, value);
	}

	public HashMap<String, String> loadFileIntoHashMap(File file) {
		HashMap<String, String> keys = new HashMap<>();
		try (FileInputStream fis = new FileInputStream(file);
			 InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8)) {
			Properties properties = new Properties();
			properties.load(isr);
			for (String key : properties.stringPropertyNames()) {
				String value = properties.getProperty(key);
				keys.put(key, String.valueOf(value));
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return keys;
	}
	
	// ---------- Loaders ----------
	
	@Override
	public void loadAllResidents() {
		loadFiles(Resident.class, (resident -> {
			// Store data.
			try {
				TownyUniverse.getInstance().addResident(resident);
			} catch (AlreadyRegisteredException e) {
				e.printStackTrace();
			}
		}));
	}

	@Override
	public void loadAllWorlds() {
		loadFiles(TownyWorld.class, (world) -> {
			// Store data.
			try {
				TownyUniverse.getInstance().addWorld(world);
			} catch (AlreadyRegisteredException e) {
				e.printStackTrace();
			}
		});
	}

	@Override
	public void loadAllNations() {
		loadFiles(Nation.class, (nation -> {
			// Store data.
			try {
				TownyUniverse.getInstance().addNation(nation);
			} catch (AlreadyRegisteredException e) {
				e.printStackTrace();
			}
		}));
	}

	@Override
	public void loadAllTowns() {
		loadFiles(Town.class, town -> {
			// Store data.
			try {
				TownyUniverse.getInstance().addTown(town);
			} catch (AlreadyRegisteredException e) {
				e.printStackTrace();
			}
		});
	}

	@Override
	public void loadAllTownBlocks() {
		loadFiles(TownBlock.class, (tb -> {
			// Store data.
			try {
				TownyUniverse.getInstance().addTownBlock(tb);
			} catch (AlreadyRegisteredException e) {
				e.printStackTrace();
			}
		}));
	}

	// ---------- Loaders ----------
	
	private <T extends Saveable> void loadFiles(@NotNull Class<T> clazz, @NotNull Consumer<T> consumer) {
		Validate.notNull(clazz);
		Validate.notNull(consumer);
		File dir = getFlatFileDirectory(clazz);
		
		// Make sure that a file wasn't given instead of a directory
		if (!dir.isDirectory()) {
			throw new TownyRuntimeException("Object of type: " + clazz + " has save path is not a directory. " + dir.getPath());
		}

		Path path = Paths.get(dir.getPath());
		
		// We have a LOT of files in our directories so lets use a directory stream to 
		// make iterating over them a lot faster.
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(path, "*.txt")) {
			for (Path p : stream) {
				T loadedObj = load(p.toFile(), clazz);

				// Log any errors but continue loading.
				if (loadedObj == null) {
					TownyMessaging.sendErrorMsg("Could not load " + p);
					continue;
				}
				
				// Consume the loaded object.
				consumer.accept(loadedObj);
			}
		} catch (IOException e) {
			throw new TownyRuntimeException(e.getMessage());
		}
	}
	
	@Override
	public void completeLoad() {
		// Post load fields
		if (postLoadFields.isEmpty())
			return;

		for (Map.Entry<Field, Map<Saveable, String>> fieldEntry : postLoadFields.entrySet()) {
			Field field = fieldEntry.getKey();

			for (Map.Entry<Saveable, String> objEntry : fieldEntry.getValue().entrySet()) {
				loadField(objEntry.getKey(), field, objEntry.getValue());
			}
		}
		
		postLoadFields.clear();
	}

	private void convertMapData(Map<String, ObjectContext> from, Map<String, String> to) {
		for (Map.Entry<String, ObjectContext> entry : from.entrySet()) {
			String valueStr = toStoredString(entry.getValue().getValue(), entry.getValue().getType());
			to.put(entry.getKey(), valueStr);
		}
	}

	private <T extends Saveable> @NotNull File getFlatFile(@NotNull Class<T> type, @NotNull UUID id) {
		Validate.notNull(type);
		Validate.notNull(id);

		File dir = getFlatFileDirectory(type);

		return new File(dir.getPath() + "/" + id + ".txt");
	}

	private <T extends Saveable> @NotNull File getFlatFileDirectory(@NotNull Class<T> type) {
		Validate.notNull(type);

		// Check the cache
		File cached = fileDirectoryCache.get(type);
		if (cached != null) {
			return cached;
		}

		SavedEntity annotation = type.getAnnotation(SavedEntity.class);
		if (annotation == null) {
			throw new TownyRuntimeException("Saveable class is not annotated with @SavedEntity.");
		}

		// Cache result.
		String fileName = Towny.getPlugin().getDataFolder() + "/data/" + annotation.directory();
		File dir = new File(fileName);
		
		// Make sure the directory exists
		if (!dir.exists() || !dir.isDirectory()) {
			if (!dir.mkdirs()) {
				throw new TownyRuntimeException("Required Directory for " + type + " could not be created.");
			}
		}
		
		fileDirectoryCache.putIfAbsent(type, dir);

		return dir;
	}

	private static void recursiveDelete(File file) {
		//to end the recursive loop
		if (!file.exists())
			return;

		//if directory, go inside and call recursively
		if (file.isDirectory()) {
			for (File f : file.listFiles()) {
				//call recursively
				recursiveDelete(f);
			}
		}
		//call delete to delete files and empty directory
		file.delete();
	}

	@Override
	public void upgrade() {

    	System.out.println("Beginning upgrade process...\nLoading from Legacy DB...");

		// Load all file from the legacy database.
		TownyUniverse.getInstance().loadLegacyDatabase("ff");
		System.out.print("Done\n");

		System.out.println("Deleting old DB files");
		// Delete all old files.
		final File dataDir = new File(TownyUniverse.getInstance().getRootFolder() + File.separator + "data");
		recursiveDelete(dataDir);
		
		// Then save it in the new format.
    	System.out.println("Saving into new DB format...");
    	saveAll();
		System.out.print("Done\n");

    	System.out.println("Database successfully updated");
	}
}
