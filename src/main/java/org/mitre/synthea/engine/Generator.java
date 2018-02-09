package org.mitre.synthea.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;



import org.mitre.synthea.datastore.DataStore;
import org.mitre.synthea.export.Exporter;
import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.helpers.SimpleCSV;
import org.mitre.synthea.helpers.TransitionMetrics;
import org.mitre.synthea.helpers.Utilities;
import org.mitre.synthea.modules.DeathModule;
import org.mitre.synthea.modules.EncounterModule;
import org.mitre.synthea.modules.LifecycleModule;
import org.mitre.synthea.world.agents.CommunityHealthWorker;
import org.mitre.synthea.world.agents.Hospital;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.Costs;
import org.mitre.synthea.world.concepts.VitalSign;
import org.mitre.synthea.world.geography.Demographics;
import org.mitre.synthea.world.geography.Location;

/**
 * Generator creates a population by running the generic modules each timestep per Person.
 */
public class Generator {

  public static final long ONE_HUNDRED_YEARS = 100L * TimeUnit.DAYS.toMillis(365);
  public static final int MAX_TRIES = 10;
  public DataStore database;
  public List<CommunityHealthWorker> chws;
  public long numberOfPeople;
  public long seed;
  private Random random;
  public long timestep;
  public long stop;
  public Map<String, AtomicInteger> stats;
  public Location location;
  private AtomicInteger totalGeneratedPopulation;
  private String logLevel;
  private boolean onlyDeadPatients;
  public TransitionMetrics metrics;
  public static final String DEFAULT_STATE = "Massachusetts";

  /**
   * Helper class following the "Parameter Object" pattern.
   * This class provides the default values for Generator, or alternatives may be set.
   */
  public static class GeneratorOptions {
    public int population = Integer.parseInt(Config.get("generate.default_population", "1"));
    public long seed = System.currentTimeMillis();
    public String city;
    public String state;
  }
  
  /**
   * Create a Generator, using all default settings.
   */
  public Generator() {
    this(new GeneratorOptions());
  }

  /**
   * Create a Generator, with the given population size.
   * All other settings are left as defaults.
   * 
   * @param population Target population size
   */
  public Generator(int population) {
    init(population, System.currentTimeMillis(), DEFAULT_STATE, null);
  }
  
  /**
   * Create a Generator, with the given population size and seed.
   * All other settings are left as defaults.
   * 
   * @param population Target population size
   * @param seed Seed used for randomness
   */
  public Generator(int population, long seed) {
    init(population, seed, DEFAULT_STATE, null);
  }

  /**
   * Create a Generator, with the given options.
   * @param o Desired configuration options
   */
  public Generator(GeneratorOptions o) {
    String state = o.state == null ? DEFAULT_STATE : o.state;
    init(o.population, o.seed, state, o.city);
  }

  private void init(int population, long seed, String state, String city) {
    String dbType = Config.get("generate.database_type");

    switch (dbType) {
      case "in-memory":
        this.database = new DataStore(false);
        break;
      case "file":
        this.database = new DataStore(true);
        break;
      case "none":
        this.database = null;
        break;
      default:
        throw new IllegalArgumentException(
            "Unexpected value for config setting generate.database_type: '" + dbType
                + "' . Valid values are file, in-memory, or none.");
    }

    this.numberOfPeople = population;
    this.chws = Collections.synchronizedList(new ArrayList<CommunityHealthWorker>());
    this.seed = seed;
    this.random = new Random(seed);
    this.timestep = Long.parseLong(Config.get("generate.timestep"));
    this.stop = System.currentTimeMillis();

    this.location = new Location(state, city);

    this.logLevel = Config.get("generate.log_patients.detail", "simple");
    this.onlyDeadPatients = Boolean.parseBoolean(Config.get("generate.only_dead_patients"));

    this.totalGeneratedPopulation = new AtomicInteger(0);
    this.stats = Collections.synchronizedMap(new HashMap<String, AtomicInteger>());
    stats.put("alive", new AtomicInteger(0));
    stats.put("dead", new AtomicInteger(0));

    if (Boolean.parseBoolean(
          Config.get("generate.track_detailed_transition_metrics", "false"))) {
      this.metrics = new TransitionMetrics();
    }

    // initialize hospitals
    Hospital.loadHospitals();
    Module.getModules(); // ensure modules load early
    CommunityHealthWorker.initalize(this.location, this.random); // ensure CHWs are set early
    Costs.loadCostData();
    
    String locationName;
    if (city == null) {
      locationName = state;
    } else {
      locationName = city + ", " + state;
    }
    System.out.println("Running with options:");
    System.out.println(String.format("Population: %d\nSeed: %d\nLocation: %s\n", 
        this.numberOfPeople, this.seed, locationName));
  }

  /**
   * Generate the population, using the currently set configuration settings.
   */
  public void run() {
    ExecutorService threadPool = Executors.newFixedThreadPool(8);

    for (int i = 0; i < this.numberOfPeople; i++) {
      final int index = i;
      final long seed = this.random.nextLong();
      threadPool.submit(() -> generatePerson(index, seed));
    }

    try {
      threadPool.shutdown();
      while (!threadPool.awaitTermination(30, TimeUnit.SECONDS)) {
        System.out.println("Waiting for threads to finish... " + threadPool);
      }
    } catch (InterruptedException e) {
      e.printStackTrace();
    }

    // have to store providers at the end to correctly capture utilization #s
    // TODO - de-dup hospitals if using a file-based database?
    if (database != null) {
      database.store(Hospital.getHospitalList());

      List<CommunityHealthWorker> chws = CommunityHealthWorker.workers.values().stream()
          .flatMap(List::stream).collect(Collectors.toList());
      database.store(chws);
    }

    Exporter.runPostCompletionExports(this);

    System.out.println(stats);

    if (this.metrics != null) {
      metrics.printStats(totalGeneratedPopulation.get());
    }
  }
  
  /**
   * Generate a completely random Person. The returned person will be alive at the end of the
   * simulation. This means that if in the course of the simulation the person dies, a new person
   * will be started to replace them. 
   * The seed used to generate the person is randomized as well.
   * 
   * @param index Target index in the whole set of people to generate
   * @return generated Person
   */
  public Person generatePerson(int index) {
    // System.currentTimeMillis is not unique enough
    long personSeed = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    return generatePerson(index, personSeed);
  }

  /**
   * Generate a random Person, from the given seed. The returned person will be alive at the end of
   * the simulation. This means that if in the course of the simulation the person dies, a new
   * person will be started to replace them. Note also that if the person dies, the seed to produce
   * them can't be re-used (otherwise the new person would die as well) so a new seed is picked,
   * based on the given seed.
   * 
   * @param index
   *          Target index in the whole set of people to generate
   * @param personSeed
   *          Seed for the random person
   * @return generated Person
   */
  public Person generatePerson(int index, long personSeed) {
    Person person = null;
    try {
      boolean isAlive = true;

      Demographics city = location.randomCity(new Random(personSeed));

      do {
        List<Module> modules = Module.getModules();

        person = new Person(personSeed);
        person.populationSeed = this.seed;

        // TODO - this is quick & easy to implement,
        // but we need to adapt the ruby method of pre-defining all the demographic buckets
        // and then putting people into those
        // -- but: how will that work with seeds?
        long start = setDemographics(person, city);
        person.attributes.put(Person.LOCATION, location);

        LifecycleModule.birth(person, start);
        EncounterModule encounterModule = new EncounterModule();

        long time = start;
        while (person.alive(time) && time < stop) {
          encounterModule.process(person, time);
          Iterator<Module> iter = modules.iterator();
          while (iter.hasNext()) {
            Module module = iter.next();
            // System.out.format("Processing module %s\n", module.name);
            if (module.process(person, time)) {
              // System.out.format("Removing module %s\n", module.name);
              iter.remove(); // this module has completed/terminated.
            }
          }
          encounterModule.endWellnessEncounter(person, time);

          // TODO: if CHW policy is enabled for community, possibly add CHW interventions
          // if true
          // then add chw encounter to record
          // and set chw variable(s) on person.attributes.put(KEY, VALUE)

          time += timestep;
        }

        DeathModule.process(person, time);

        isAlive = person.alive(time);

        if (isAlive && onlyDeadPatients) {
          // rotate the seed so the next attempt gets a consistent but different one
          personSeed = new Random(personSeed).nextLong();
          continue;
          // skip the other stuff if the patient is alive and we only want dead patients
          // note that this skips ahead to the while check and doesn't automatically re-loop
        }

        if (database != null) {
          database.store(person);
        }

        if (this.metrics != null) {
          metrics.recordStats(person, time);
        }

        if (!this.logLevel.equals("none")) {
          writeToConsole(person, index, time, isAlive);
        }

        String key = isAlive ? "alive" : "dead";

        AtomicInteger count = stats.get(key);
        count.incrementAndGet();

        totalGeneratedPopulation.incrementAndGet();
        
        if (!isAlive) {
          // rotate the seed so the next attempt gets a consistent but different one
          personSeed = new Random(personSeed).nextLong();
        }

        // TODO - export is DESTRUCTIVE when it filters out data
        // this means export must be the LAST THING done with the person
        Exporter.export(person, time);
      } while ((!isAlive && !onlyDeadPatients) || (isAlive && onlyDeadPatients));
      // if the patient is alive and we want only dead ones => loop & try again
      //  (and dont even export, see above)
      // if the patient is dead and we only want dead ones => done
      // if the patient is dead and we want live ones => loop & try again
      //  (but do export the record anyway)
      // if the patient is alive and we want live ones => done
    } catch (Throwable e) {
      // lots of fhir things throw errors for some reason
      e.printStackTrace();
      throw e;
    }
    return person;
  }

  private synchronized void writeToConsole(Person person, int index, long time, boolean isAlive) {
    // this is synchronized to ensure all lines for a single person are always printed 
    // consecutively
    String deceased = isAlive ? "" : "DECEASED";
    System.out.format("%d -- %s (%d y/o) %s, %s %s\n", index + 1, 
        person.attributes.get(Person.NAME), person.ageInYears(time), 
        person.attributes.get(Person.CITY), person.attributes.get(Person.STATE),
        deceased);

    if (this.logLevel.equals("detailed")) {
      System.out.println("ATTRIBUTES");
      for (String attribute : person.attributes.keySet()) {
        System.out.format("  * %s = %s\n", attribute, person.attributes.get(attribute));
      }
      System.out.format("SYMPTOMS: %d\n", person.symptomTotal());
      System.out.println(person.record.textSummary());
      System.out.println("VITAL SIGNS");
      for (VitalSign vitalSign : person.vitalSigns.keySet()) {
        System.out.format("  * %25s = %6.2f\n", vitalSign,
            person.getVitalSign(vitalSign).doubleValue());
      }
      System.out.format("Number of CHW Interventions: %d\n",
          person.attributes.get(Person.CHW_INTERVENTION));
      System.out.println("-----");
    }
  }

  private long setDemographics(Person person, Demographics city) {
	  // Create map and read in the sampled SPEW csv file for Massachusetts 

	  List<LinkedHashMap<String, String>> spewPerson;

	  try {
		  spewPerson = SimpleCSV.parse(Utilities.readResource("people25.csv"));
	  } catch (IOException e) {
		  e.printStackTrace();
		  return (Long) null;
	  }

	  // get a random spew person

	  int[] range = new int[] {1,spewPerson.size() + 1};

	  int rand_spew = (int) person.rand(range);

	  String spew_serial = spewPerson.get(rand_spew).get("SERIALNO");
	  person.attributes.put(Person.SPEW_SERIAL_NO, spew_serial);

	  String household_income = spewPerson.get(rand_spew).get("HINCP");
	  person.attributes.put(Person.HOUSEHOLD_INCOME, household_income);

	  String household_size = spewPerson.get(rand_spew).get("NP");
	  person.attributes.put(Person.HOUSEHOLD_SIZE, household_size);

	  //this will have to change based on SPEW latitude/longitude   
	  person.attributes.put(Person.CITY, city.city);

	  //TODO spew location changes
	  person.attributes.put(Person.STATE, city.state);

	  String race = spewPerson.get(rand_spew).get("RAC1P");
	  String hisp = spewPerson.get(rand_spew).get("HISP");

	  //race codes, hispanic is a different variable
	  if(race.equals("1")){
		  person.attributes.put(Person.RACE, "white");
	  } else if(race.equals("2")){
		  person.attributes.put(Person.RACE, "black");
	  } else if(race.equals("3")){
		  person.attributes.put(Person.RACE, "native");
	  } else if(race.equals("4")){
		  person.attributes.put(Person.RACE, "native");
	  } else if(race.equals("5")){
		  person.attributes.put(Person.RACE, "native");
	  } else if(race.equals("6")){
		  person.attributes.put(Person.RACE, "asian");
	  } else if(race.equals("7")){
		  person.attributes.put(Person.RACE, "asian");
	  } else if(race.equals("8")){
		  person.attributes.put(Person.RACE, "other");
	  } else if(race.equals("9")){
		  person.attributes.put(Person.RACE, "other");
	  }

	  //TODO hispanic ethnicities that are in SPEW but not in synthea

	  //hispanic codes CSV for lookup
	  List<LinkedHashMap<String, String>> hispanic_codes;

	  try {
		  hispanic_codes = SimpleCSV.parse(Utilities.readResource("hispanic.csv"));
	  } catch (IOException e) {
		  e.printStackTrace();
		  return (Long) null;
	  }

	  if(person.attributes.get(race) == null && !hisp.equals("1")){
		  person.attributes.put(Person.RACE, "hispanic");
		  person.attributes.put(Person.HISPANIC, true);
		  
		  for(int i = 1;i<=hispanic_codes.size()-1;i++) {
			  if(spewPerson.get(rand_spew).get("HISP").equals(hispanic_codes.get(i).get("Code"))) {
				  person.attributes.put(Person.ETHNICITY, hispanic_codes.get(i).get("Ethnicity"));
			  }
		  }
	  }
	
	  if(hisp.equals("1")){
		  person.attributes.put(Person.HISPANIC, false);
		  String ethnicity = city.ethnicityFromRace((String)person.attributes.get(Person.RACE), person);
		  person.attributes.put(Person.ETHNICITY, ethnicity);
	  }
 	  
	  String language = city.languageFromEthnicity((String) person.attributes.get(Person.ETHNICITY),person);
	  person.attributes.put(Person.FIRST_LANGUAGE, language);

	  String gender = spewPerson.get(rand_spew).get("SEX");

	  if(gender.equals("1")){
		  person.attributes.put(Person.GENDER, "M");
	  } else if (gender.equals("2")){
		  person.attributes.put(Person.GENDER, "F");
	  }

	  //Longitude and latitude
	  //TODO a look up to assign address/city/town/zip from lat and long
	  //look into using FIPS codes
	
	  String longitude = spewPerson.get(rand_spew).get("longitude");
	  person.attributes.put(Person.LONGITUDE, longitude);
	
	  String latitude = spewPerson.get(rand_spew).get("latitude");
	  person.attributes.put(Person.LATITUDE, latitude);

	  String nativity = spewPerson.get(rand_spew).get("NATIVITY");
	
	  if(nativity.equals("1")) {
		  person.attributes.put(Person.NATIVITY, "native");
	  } else if(nativity.equals("2")) {
		  person.attributes.put(Person.NATIVITY, "foreign_born");
	  }
	
	  List<LinkedHashMap<String, String>> birthplaces;
	  
	  try {
		  birthplaces = SimpleCSV.parse(Utilities.readResource("hispanic.csv"));
	  } catch (IOException e) {
		  e.printStackTrace();
		  return (Long) null;
	  }
	  
	  for(int i = 1;i<=birthplaces.size()-1;i++) {
		  if(spewPerson.get(rand_spew).get("POBP").equals(birthplaces.get(i).get("pums_code"))) {
			  person.attributes.put(Person.BIRTHPLACE, birthplaces.get(i).get("pob"));
		  }
	  }

	  String school_enrollment = spewPerson.get(rand_spew).get("SCH");

	  if(school_enrollment.equals("NA")){
		  person.attributes.put(Person.SCHOOL_ENROLLMENT, "N/A (less than 3 years old)");
	  } else if (school_enrollment.equals("1")){
		  person.attributes.put(Person.SCHOOL_ENROLLMENT, "no");
	  } else if (school_enrollment.equals("2")){
		  person.attributes.put(Person.SCHOOL_ENROLLMENT, "public_school_or_public_college");
	  } else if (school_enrollment.equals("3")){
		  person.attributes.put(Person.SCHOOL_ENROLLMENT, "private_school_or_college_or_home_school");
	  } 

	  String grade_level = spewPerson.get(rand_spew).get("SCHG");

	  if(grade_level.equals("NA")) {
		  person.attributes.put(Person.GRADE_LEVEL, "not_attending_school");
	  } else if(grade_level.equals("1")) {
		  person.attributes.put(Person.GRADE_LEVEL, "nursery_school_or_preschool");
	  } else if(grade_level.equals("2")) {
		  person.attributes.put(Person.GRADE_LEVEL, "kindergarten");
	  } else if(grade_level.equals("3")) {
		  person.attributes.put(Person.GRADE_LEVEL, "grade_1");
	  } else if(grade_level.equals("4")) {
		  person.attributes.put(Person.GRADE_LEVEL, "grade_2");
	  } else if(grade_level.equals("5")) {
		  person.attributes.put(Person.GRADE_LEVEL, "grade_3");
	  } else if(grade_level.equals("6")) {
		  person.attributes.put(Person.GRADE_LEVEL, "grade_4");
	  } else if(grade_level.equals("7")) {
		  person.attributes.put(Person.GRADE_LEVEL, "grade_5");
	  } else if(grade_level.equals("8")) {
		  person.attributes.put(Person.GRADE_LEVEL, "grade_6");
	  } else if(grade_level.equals("9")) {
		  person.attributes.put(Person.GRADE_LEVEL, "grade_7");
	  } else if(grade_level.equals("10")) {
		  person.attributes.put(Person.GRADE_LEVEL, "grade_8");
	  } else if(grade_level.equals("11")) {
		  person.attributes.put(Person.GRADE_LEVEL, "grade_9");
	  } else if(grade_level.equals("12")) {
		  person.attributes.put(Person.GRADE_LEVEL, "grade_10");
	  } else if(grade_level.equals("13")) {
		  person.attributes.put(Person.GRADE_LEVEL, "grade_11");
	  } else if(grade_level.equals("14")) {
		  person.attributes.put(Person.GRADE_LEVEL, "grade_12");
	  } else if(grade_level.equals("15")) {
		  person.attributes.put(Person.GRADE_LEVEL, "college_undergraduate");
	  } else if(grade_level.equals("1")) {
		  person.attributes.put(Person.GRADE_LEVEL, "graduate_or_professional_school");
	  }

	  String relationship = spewPerson.get(rand_spew).get("RELP");

	  if(relationship.equals("0")) {
		  person.attributes.put(Person.RELATIONSHIP, "reference_person");
	  } else if(relationship.equals("1")) {
		  person.attributes.put(Person.RELATIONSHIP, "husband_or_wife");
	  } else if(relationship.equals("2")) {
		  person.attributes.put(Person.RELATIONSHIP, "biological_son_or_dsughter");
	  } else if(relationship.equals("3")) {
		  person.attributes.put(Person.RELATIONSHIP, "adopted_son_or_daughter");
	  } else if(relationship.equals("4")) {
		  person.attributes.put(Person.RELATIONSHIP, "stepson_or_stepdaughter");
	  } else if(relationship.equals("5")) {
		  person.attributes.put(Person.RELATIONSHIP, "brother_or_sister");
	  } else if(relationship.equals("6")) {
		  person.attributes.put(Person.RELATIONSHIP, "father_or_mother");
	  } else if(relationship.equals("7")) {
		  person.attributes.put(Person.RELATIONSHIP, "grandchild");
	  } else if(relationship.equals("8")) {
		  person.attributes.put(Person.RELATIONSHIP, "parent_in_law");
	  } else if(relationship.equals("9")) {
		  person.attributes.put(Person.RELATIONSHIP, "son_in_law_or_daughter_in_law");
	  } else if(relationship.equals("10")) {
		  person.attributes.put(Person.RELATIONSHIP, "other_relative");
	  } else if(relationship.equals("11")) {
		  person.attributes.put(Person.RELATIONSHIP, "roomer_or_boarder");
	  } else if(relationship.equals("12")) {
		  person.attributes.put(Person.RELATIONSHIP, "housemate_or_roommate");
	  } else if(relationship.equals("13")) {
		  person.attributes.put(Person.RELATIONSHIP, "unmarried_partner");
	  } else if(relationship.equals("14")) {
		  person.attributes.put(Person.RELATIONSHIP, "foster_child");
	  } else if(relationship.equals("15")) {
		  person.attributes.put(Person.RELATIONSHIP, "other_nonrelative");
	  } else if(relationship.equals("16")) {
		  person.attributes.put(Person.RELATIONSHIP, "institutionalized_group_quarters_population");
	  } else if(relationship.equals("17")) {
		  person.attributes.put(Person.RELATIONSHIP, "noninstitutionalized_group_quarters_population");
	  }


	  // Socioeconomic variables of education, income, and education are set.
	  String education = city.pickEducation(person.random);
	  person.attributes.put(Person.EDUCATION, education);
	  double educationLevel = city.educationLevel(education, person);
	  person.attributes.put(Person.EDUCATION_LEVEL, educationLevel);

	  //everyone under 15 has blank income
	  //use household income, otherwise use personal income


	  //TODO: there are some negative incomes in the SPEW data
	  //probably a SPEW error. resolve in R?

	  long targetAge = Long.valueOf(spewPerson.get(rand_spew).get("AGEP")).longValue();

	  int income = Integer.parseInt(spewPerson.get(rand_spew).get("PINCP"));
	  person.attributes.put(Person.INCOME, income);
	  
	  double incomeLevel = city.incomeLevel(Integer.parseInt(spewPerson.get(rand_spew).get("HINCP")));
	  person.attributes.put(Person.INCOME_LEVEL, incomeLevel);
	  
	  double occupation = person.rand();
	  person.attributes.put(Person.OCCUPATION_LEVEL, occupation);
	  
	  double sesScore = city.socioeconomicScore(incomeLevel, educationLevel, occupation);
	  person.attributes.put(Person.SOCIOECONOMIC_SCORE, sesScore);
	  person.attributes.put(Person.SOCIOECONOMIC_CATEGORY, city.socioeconomicCategory(sesScore));
	  

	  String spew_occupation = spewPerson.get(rand_spew).get("OCCP");
	  //0010 - 3540  Management, Business, Science, and Arts Occupations
	  //3600 - 4650  Service Occupations
	  //4700 - 5940  Sales and Office Occupations
	  //6000 - 7630  Natural Resources, Construction, and Maintenance Occupations
	  //7700 - 9750  Production, Transportation, and Material Moving Occupations


	  //everyone under 15 has blank occupation
	  //TODO lookup table for occupation

	  String employment_status = spewPerson.get(rand_spew).get("ESR");

	  if(employment_status.equals("")) {
		  person.attributes.put(Person.EMPLOYMENT_STATUS, "na_under_16");
	  } else if(employment_status.equals("")) {
		  person.attributes.put(Person.EMPLOYMENT_STATUS, "civilian_employed_at_work");
	  } else if(employment_status.equals("")) {
		  person.attributes.put(Person.EMPLOYMENT_STATUS, "civilian_employed_with_job_but_not_at_work");
	  } else if(employment_status.equals("")) {
		  person.attributes.put(Person.EMPLOYMENT_STATUS, "unemployed");
	  } else if(employment_status.equals("")) {
		  person.attributes.put(Person.EMPLOYMENT_STATUS, "armed_forces_at_work");
	  } else if(employment_status.equals("")) {
		  person.attributes.put(Person.EMPLOYMENT_STATUS, "armed_forces_with_job_but_not_at_work");
	  } else if(employment_status.equals("")) {
		  person.attributes.put(Person.EMPLOYMENT_STATUS, "not_in_labor_force");
	  }

	  // TODO this is terrible date handling, figure out how to use the java time library
	  long earliestBirthdate = stop - TimeUnit.DAYS.toMillis((targetAge + 1) * 365L + 1);
	  long latestBirthdate = stop - TimeUnit.DAYS.toMillis(targetAge * 365L);

	  long birthdate = (long) person.rand(earliestBirthdate, latestBirthdate);

	  return birthdate;
  }
}