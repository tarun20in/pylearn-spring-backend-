package com.pylearn.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class RoverSimulationService {

    private static final Logger log = LoggerFactory.getLogger(RoverSimulationService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path scriptPath = Paths.get("scripts", "pyrover_simulation.py");

    public Map<String, Object> runSimulation(String code, String missionId, Integer seed, Map<String, Object> customConfig) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (code == null || code.trim().isEmpty()) {
            response.put("success", false);
            response.put("error", "Code cannot be empty. Please write Python rover logic.");
            return response;
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("code", code);
            payload.put("missionId", missionId != null ? missionId : "free_explore");
            payload.put("seed", seed != null ? seed : 42);
            if (customConfig != null) {
                payload.put("customConfig", customConfig);
            }

            String jsonInput = objectMapper.writeValueAsString(payload);

            ProcessBuilder pb = new ProcessBuilder("python", scriptPath.toAbsolutePath().toString());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Send payload via STDIN
            try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(jsonInput);
                writer.flush();
            }

            // Read output
            StringBuilder outputBuf = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputBuf.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                response.put("success", false);
                response.put("error", "Rover simulation timed out (max 10 seconds). Check for infinite while loops.");
                return response;
            }

            String fullOutput = outputBuf.toString();
            String prefix = "__PYROVER_SIMULATION_RESULT__:";
            int idx = fullOutput.indexOf(prefix);
            if (idx != -1) {
                String jsonStr = fullOutput.substring(idx + prefix.length()).trim();
                return objectMapper.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
            } else {
                log.warn("Rover script finished without expected prefix. Output: {}", fullOutput);
                response.put("success", false);
                response.put("error", "Failed to parse simulation result: " + fullOutput.trim());
                return response;
            }

        } catch (Exception e) {
            log.error("Rover simulation error: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("error", "Internal simulation error: " + e.getMessage());
            return response;
        }
    }

    public List<Map<String, Object>> getAvailableMissions() {
        List<Map<String, Object>> missions = new ArrayList<>();

        missions.add(Map.of(
                "id", "free_explore",
                "title", "Planetary Odyssey (Free Sandbox)",
                "difficulty", "Creative",
                "badge", "Sandbox",
                "description", "Open Martian terrain with crystal clusters, basalt obstacles, and monoliths. Experiment freely with loops, functions, and navigation algorithms.",
                "starterCode", "# PyRover 3D - Free Exploration Sandbox\n# Explore the terrain and harvest crystals!\n\nfor i in range(12):\n    rover.forward(2)\n    if rover.detect_crystal():\n        rover.mine()\n        print(f\"Harvested crystal at {rover.get_pos()}! Energy: {rover.energy}%\")\n    if rover.is_obstacle_ahead():\n        rover.turn_right()\n    else:\n        rover.turn_left()\n"
        ));

        missions.add(Map.of(
                "id", "first_landing",
                "title", "Mission 1: First Landing",
                "difficulty", "Beginner",
                "badge", "Sequencing",
                "description", "Drive across the landing corridor to reach the Alpha Relay and collect the energy crystals on the pad.",
                "starterCode", "# Mission 1: First Landing\n# Drive forward and harvest the power crystals\n\nfor step in range(8):\n    rover.forward()\n    if rover.detect_crystal():\n        rover.mine()\n        rover.place_beacon(\"POWER_CELL\")\n        print(f\"Extracted power crystal! Total: {rover.crystals}\")\n"
        ));

        missions.add(Map.of(
                "id", "spiral_harvester",
                "title", "Mission 2: Spiral Harvester",
                "difficulty", "Intermediate",
                "badge", "Nested Loops",
                "description", "The planetary crystal vein radiates in an outward spiral. Write an expanding spiral loop to harvest all 16 crystals.",
                "starterCode", "# Mission 2: Spiral Harvester\n# Harvest crystals expanding outward from Base Camp\n\nstep_size = 1\nfor cycle in range(6):\n    for side in range(2):\n        for s in range(step_size):\n            rover.forward()\n            if rover.detect_crystal():\n                rover.mine()\n                print(f\"Mined crystal at {rover.get_pos()}\")\n        rover.turn_right()\n    step_size += 1\n"
        ));

        missions.add(Map.of(
                "id", "crater_labyrinth",
                "title", "Mission 3: Labyrinth of Craters",
                "difficulty", "Intermediate",
                "badge", "Conditionals & Mazes",
                "description", "A dense field of basalt rock boulders blocks the path to the Deep Core monolith. Use a wall-following algorithm to navigate the maze.",
                "starterCode", "# Mission 3: Crater Labyrinth\n# Navigate around obstacles using right-hand wall follower\n\nfor move in range(60):\n    # Try to turn right if clear, else forward, else left\n    rover.turn_right()\n    if not rover.is_obstacle_ahead():\n        rover.forward()\n    else:\n        rover.turn_left()\n        if not rover.is_obstacle_ahead():\n            rover.forward()\n        else:\n            rover.turn_left()\n    if rover.detect_crystal():\n        rover.mine()\n"
        ));

        missions.add(Map.of(
                "id", "crystal_sorter",
                "title", "Mission 4: The Crystal Sorter",
                "difficulty", "Advanced",
                "badge", "Data Structures",
                "description", "Scan the grid and catalog ruby, sapphire, and emerald crystals into Python lists or dictionaries.",
                "starterCode", "# Mission 4: The Crystal Sorter\n# Catalog crystals by coordinate and color\ncatalog = {}\n\nfor x in range(4):\n    for y in range(4):\n        tile = rover.scan()\n        if tile == 'crystal':\n            rover.mine()\n            catalog[rover.get_pos()] = 'Mined'\n        rover.forward()\n    rover.turn_right()\n    rover.forward()\n    rover.turn_right()\n\nprint(\"Cataloged crystals:\", catalog)\n"
        ));

        return missions;
    }
}
