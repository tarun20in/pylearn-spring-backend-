#!/usr/bin/env python3
"""
AstroBot 3D - Planetary Exploration Rover Simulation Engine
Executes student Python scripts within a deterministic simulated 3D world,
recording a timeline of actions, movements, scans, and mining events.
"""

import sys
import json
import random
import traceback
import inspect

MAX_SIMULATION_STEPS = 2500

class RoverEnvironment:
    HEADINGS = ['NORTH', 'EAST', 'SOUTH', 'WEST']
    DIR_DELTAS = {
        'NORTH': (0, -1),
        'EAST':  (1, 0),
        'SOUTH': (0, 1),
        'WEST':  (-1, 0)
    }

    def __init__(self, mission_id="free_explore", seed=42, width=16, height=16, custom_config=None):
        self.mission_id = mission_id
        self.width = width
        self.height = height
        self.seed = seed
        random.seed(seed)

        self.rover_x = width // 2
        self.rover_z = height // 2
        self.rover_heading = 'NORTH'
        self.rover_energy = 100
        self.crystals_mined = 0
        self.beacons = []
        self.logs = []
        self.actions = []
        self.step_count = 0
        self.aborted = False
        self.error_message = None

        # Grid matrix: (x, z) -> {'type': 'empty' | 'crystal' | 'rock' | 'monolith' | 'beacon', ...}
        self.grid = {}
        self.setup_world(custom_config)

    def setup_world(self, custom_config=None):
        # Default empty tiles
        for x in range(self.width):
            for z in range(self.height):
                self.grid[(x, z)] = {"type": "empty"}

        if self.mission_id == "first_landing":
            # Simple landing strip with crystal at end
            self.rover_x, self.rover_z = 2, 8
            self.rover_heading = 'EAST'
            # Place target beacon & power crystals
            self.grid[(10, 8)] = {"type": "crystal", "color": "cyan", "value": 100}
            self.grid[(6, 8)] = {"type": "crystal", "color": "cyan", "value": 50}
            self.grid[(12, 8)] = {"type": "monolith", "name": "Alpha Relay"}
            # Border rocks
            for x in range(self.width):
                self.grid[(x, 6)] = {"type": "rock"}
                self.grid[(x, 10)] = {"type": "rock"}

        elif self.mission_id == "spiral_harvester":
            # Crystals placed in an outward spiral
            cx, cz = self.width // 2, self.height // 2
            self.rover_x, self.rover_z = cx, cz
            self.rover_heading = 'NORTH'
            # Spiral coords: (dx, dz)
            spiral_offsets = [
                (0, -1), (1, -1), (1, 0), (1, 1),
                (0, 1), (-1, 1), (-2, 1), (-2, 0),
                (-2, -1), (-2, -2), (-1, -2), (0, -2),
                (1, -2), (2, -2), (2, -1), (2, 0)
            ]
            colors = ["cyan", "magenta", "gold", "emerald"]
            for i, (dx, dz) in enumerate(spiral_offsets):
                tx, tz = cx + dx, cz + dz
                if 0 <= tx < self.width and 0 <= tz < self.height:
                    self.grid[(tx, tz)] = {"type": "crystal", "color": colors[i % len(colors)], "value": 25}

        elif self.mission_id == "crater_labyrinth":
            # Maze of rocks
            self.rover_x, self.rover_z = 1, 1
            self.rover_heading = 'EAST'
            for x in range(self.width):
                for z in range(self.height):
                    if x == 0 or x == self.width - 1 or z == 0 or z == self.height - 1:
                        self.grid[(x, z)] = {"type": "rock"}
                    elif (x % 2 == 0 and z % 2 == 0) or (x in (4, 8, 12) and z in (3, 7, 11)):
                        self.grid[(x, z)] = {"type": "rock"}
                    elif random.random() < 0.12 and (x, z) != (1, 1):
                        self.grid[(x, z)] = {"type": "crystal", "color": "gold", "value": 50}
            self.grid[(self.width - 2, self.height - 2)] = {"type": "monolith", "name": "Deep Core"}

        elif self.mission_id == "crystal_sorter":
            # Scattered colored crystals
            self.rover_x, self.rover_z = self.width // 2, self.height // 2
            self.rover_heading = 'NORTH'
            crystal_palette = [("ruby", 30), ("sapphire", 40), ("emerald", 50)]
            for _ in range(18):
                rx = random.randint(1, self.width - 2)
                rz = random.randint(1, self.height - 2)
                if (rx, rz) != (self.rover_x, self.rover_z):
                    c_type, val = random.choice(crystal_palette)
                    self.grid[(rx, rz)] = {"type": "crystal", "color": c_type, "value": val}

        else: # "free_explore" or custom
            # Open procedurally populated landscape
            self.rover_x, self.rover_z = self.width // 2, self.height // 2
            self.rover_heading = 'NORTH'
            for _ in range(int(self.width * self.height * 0.08)):
                rx = random.randint(1, self.width - 2)
                rz = random.randint(1, self.height - 2)
                if (rx, rz) != (self.rover_x, self.rover_z):
                    self.grid[(rx, rz)] = {"type": "crystal", "color": random.choice(["cyan", "magenta", "gold", "emerald"]), "value": 20}
            for _ in range(int(self.width * self.height * 0.06)):
                rx = random.randint(1, self.width - 2)
                rz = random.randint(1, self.height - 2)
                if (rx, rz) != (self.rover_x, self.rover_z) and self.grid[(rx, rz)]["type"] == "empty":
                    self.grid[(rx, rz)] = {"type": "rock"}
            # Monolith
            mx = (self.rover_x + 5) % (self.width - 2) + 1
            mz = (self.rover_z + 4) % (self.height - 2) + 1
            self.grid[(mx, mz)] = {"type": "monolith", "name": "Cosmic Nexus"}

    def _get_caller_line(self):
        # Walk up stack to find caller inside the student script
        frame = inspect.currentframe()
        try:
            while frame:
                if frame.f_code.co_filename == '<student_code>':
                    return frame.f_lineno
                frame = frame.f_back
        finally:
            del frame
        return 1

    def _record_action(self, action_type, details=None):
        if self.aborted:
            return
        self.step_count += 1
        if self.step_count > MAX_SIMULATION_STEPS:
            self.aborted = True
            self.error_message = f"Simulation step quota exceeded ({MAX_SIMULATION_STEPS} steps). Possible infinite loop in student code."
            raise RuntimeError(self.error_message)

        line = self._get_caller_line()
        action_obj = {
            "step": self.step_count,
            "line": line,
            "type": action_type,
            "pos": [self.rover_x, self.rover_z],
            "heading": self.rover_heading,
            "energy": max(0, self.rover_energy),
            "crystals": self.crystals_mined
        }
        if details:
            action_obj.update(details)
        self.actions.append(action_obj)

    # --- Student Rover API Implementation ---

    def forward(self, steps=1):
        for _ in range(int(steps)):
            if self.aborted: break
            dx, dz = self.DIR_DELTAS[self.rover_heading]
            nx, nz = self.rover_x + dx, self.rover_z + dz
            # Check bounds
            if not (0 <= nx < self.width and 0 <= nz < self.height):
                self._record_action("COLLIDE_BOUNDS", {"attempted": [nx, nz]})
                return False
            # Check obstacles
            tile = self.grid.get((nx, nz), {"type": "empty"})
            if tile["type"] in ("rock", "monolith"):
                self._record_action("COLLIDE_OBSTACLE", {"obstacle": tile["type"], "at": [nx, nz]})
                return False

            old_pos = [self.rover_x, self.rover_z]
            self.rover_x = nx
            self.rover_z = nz
            self.rover_energy = max(0, self.rover_energy - 0.2)
            self._record_action("FORWARD", {"from": old_pos, "to": [nx, nz]})
        return True

    def backward(self, steps=1):
        for _ in range(int(steps)):
            if self.aborted: break
            dx, dz = self.DIR_DELTAS[self.rover_heading]
            nx, nz = self.rover_x - dx, self.rover_z - dz
            if not (0 <= nx < self.width and 0 <= nz < self.height):
                self._record_action("COLLIDE_BOUNDS", {"attempted": [nx, nz]})
                return False
            tile = self.grid.get((nx, nz), {"type": "empty"})
            if tile["type"] in ("rock", "monolith"):
                self._record_action("COLLIDE_OBSTACLE", {"obstacle": tile["type"], "at": [nx, nz]})
                return False

            old_pos = [self.rover_x, self.rover_z]
            self.rover_x = nx
            self.rover_z = nz
            self.rover_energy = max(0, self.rover_energy - 0.2)
            self._record_action("BACKWARD", {"from": old_pos, "to": [nx, nz]})
        return True

    def turn_left(self):
        curr_idx = self.HEADINGS.index(self.rover_heading)
        self.rover_heading = self.HEADINGS[(curr_idx - 1) % 4]
        self._record_action("TURN_LEFT", {"heading": self.rover_heading})
        return self.rover_heading

    def turn_right(self):
        curr_idx = self.HEADINGS.index(self.rover_heading)
        self.rover_heading = self.HEADINGS[(curr_idx + 1) % 4]
        self._record_action("TURN_RIGHT", {"heading": self.rover_heading})
        return self.rover_heading

    def jump(self):
        dx, dz = self.DIR_DELTAS[self.rover_heading]
        nx, nz = self.rover_x + dx * 2, self.rover_z + dz * 2
        if 0 <= nx < self.width and 0 <= nz < self.height:
            tile = self.grid.get((nx, nz), {"type": "empty"})
            if tile["type"] not in ("rock", "monolith"):
                old_pos = [self.rover_x, self.rover_z]
                self.rover_x, self.rover_z = nx, nz
                self.rover_energy = max(0, self.rover_energy - 1.0)
                self._record_action("JUMP", {"from": old_pos, "to": [nx, nz]})
                return True
        self._record_action("JUMP_FAILED")
        return False

    def scan(self, dx=0, dz=0):
        # Look at target tile (relative or current)
        target_x = self.rover_x + dx
        target_z = self.rover_z + dz
        if not (0 <= target_x < self.width and 0 <= target_z < self.height):
            tile_info = {"type": "out_of_bounds"}
        else:
            tile_info = dict(self.grid.get((target_x, target_z), {"type": "empty"}))
        self._record_action("SCAN", {"target": [target_x, target_z], "result": tile_info["type"], "details": tile_info})
        return tile_info.get("type", "empty")

    def detect_crystal(self):
        # Check current tile or ahead tile
        curr = self.grid.get((self.rover_x, self.rover_z), {"type": "empty"})
        if curr["type"] == "crystal":
            self._record_action("DETECT_CRYSTAL", {"result": True, "at": "current"})
            return True
        dx, dz = self.DIR_DELTAS[self.rover_heading]
        ahead = self.grid.get((self.rover_x + dx, self.rover_z + dz), {"type": "empty"})
        res = (ahead["type"] == "crystal")
        self._record_action("DETECT_CRYSTAL", {"result": res, "at": "ahead"})
        return res

    def is_obstacle_ahead(self):
        dx, dz = self.DIR_DELTAS[self.rover_heading]
        nx, nz = self.rover_x + dx, self.rover_z + dz
        if not (0 <= nx < self.width and 0 <= nz < self.height):
            return True
        tile = self.grid.get((nx, nz), {"type": "empty"})
        return tile["type"] in ("rock", "monolith")

    def mine(self):
        # Check current tile first, then ahead tile
        target_tile = (self.rover_x, self.rover_z)
        if self.grid.get(target_tile, {}).get("type") != "crystal":
            dx, dz = self.DIR_DELTAS[self.rover_heading]
            ahead_tile = (self.rover_x + dx, self.rover_z + dz)
            if self.grid.get(ahead_tile, {}).get("type") == "crystal":
                target_tile = ahead_tile
            else:
                self._record_action("MINE_MISS", {"pos": [self.rover_x, self.rover_z]})
                return False

        crystal_data = self.grid[target_tile]
        self.grid[target_tile] = {"type": "empty"}
        self.crystals_mined += 1
        self.rover_energy = min(100, self.rover_energy + 5)
        self._record_action("MINE", {
            "pos": list(target_tile),
            "color": crystal_data.get("color", "cyan"),
            "value": crystal_data.get("value", 20),
            "totalMined": self.crystals_mined
        })
        return True

    def place_beacon(self, label=""):
        beacon_id = f"b_{len(self.beacons) + 1}"
        self.beacons.append({"id": beacon_id, "pos": [self.rover_x, self.rover_z], "label": str(label)})
        self._record_action("PLACE_BEACON", {"pos": [self.rover_x, self.rover_z], "label": str(label)})
        return beacon_id

    def get_pos(self):
        return (self.rover_x, self.rover_z)

    def get_heading(self):
        return self.rover_heading

    @property
    def x(self):
        return self.rover_x

    @property
    def z(self):
        return self.rover_z

    @property
    def energy(self):
        return round(self.rover_energy, 1)

    @property
    def crystals(self):
        return self.crystals_mined


class RoverProxy:
    """Wrapper exposing student-friendly attributes and methods."""
    def __init__(self, env: RoverEnvironment):
        self._env = env

    def forward(self, steps=1): return self._env.forward(steps)
    def backward(self, steps=1): return self._env.backward(steps)
    def turn_left(self): return self._env.turn_left()
    def turn_right(self): return self._env.turn_right()
    def jump(self): return self._env.jump()
    def scan(self, dx=0, dz=0): return self._env.scan(dx, dz)
    def detect_crystal(self): return self._env.detect_crystal()
    def is_obstacle_ahead(self): return self._env.is_obstacle_ahead()
    def mine(self): return self._env.mine()
    def place_beacon(self, label=""): return self._env.place_beacon(label)
    def get_pos(self): return self._env.get_pos()
    def get_heading(self): return self._env.get_heading()

    @property
    def x(self): return self._env.x
    @property
    def z(self): return self._env.z
    @property
    def energy(self): return self._env.energy
    @property
    def crystals(self): return self._env.crystals


def run_simulation(code: str, mission_id="free_explore", seed=42, custom_config=None):
    env = RoverEnvironment(mission_id=mission_id, seed=seed, custom_config=custom_config)
    rover_proxy = RoverProxy(env)

    # Hijack print to capture simulation logs
    output_logs = []
    def custom_print(*args, **kwargs):
        sep = kwargs.get('sep', ' ')
        msg = sep.join(str(a) for a in args)
        output_logs.append(msg)
        env._record_action("LOG", {"message": msg})

    # Prepare sandbox execution scope
    exec_globals = {
        '__name__': '__main__',
        'rover': rover_proxy,
        'print': custom_print,
        'range': range,
        'len': len,
        'int': int,
        'float': float,
        'str': str,
        'bool': bool,
        'list': list,
        'dict': dict,
        'set': set,
        'tuple': tuple,
        'min': min,
        'max': max,
        'sum': sum,
        'abs': abs,
        'enumerate': enumerate,
        'zip': zip,
        'sorted': sorted,
        'reversed': reversed,
    }

    error_str = None
    try:
        compiled = compile(code, '<student_code>', 'exec')
        exec(compiled, exec_globals)
    except Exception as ex:
        if env.error_message:
            error_str = env.error_message
        else:
            tb_lines = traceback.format_exception(*sys.exc_info())
            # Find line in <student_code>
            filtered = [l for l in tb_lines if '<student_code>' in l or 'Exception' in l or 'Error' in l]
            error_str = "".join(filtered[-3:]) if filtered else str(ex)

    # Serialize initial world objects for frontend rendering
    initial_crystals = [
        {"x": x, "z": z, "color": data.get("color", "cyan"), "value": data.get("value", 20)}
        for (x, z), data in env.grid.items() if data["type"] == "crystal"
    ]
    obstacles = [
        {"x": x, "z": z, "type": data["type"], "name": data.get("name", "")}
        for (x, z), data in env.grid.items() if data["type"] in ("rock", "monolith")
    ]

    # Evaluate mission success criteria
    mission_completed = False
    mission_message = ""
    if mission_id == "first_landing":
        mission_completed = env.crystals_mined >= 1 or env.rover_x >= 10
        mission_message = "Touchdown confirmed! Waypoint achieved." if mission_completed else "Drive to the Alpha Relay beacon."
    elif mission_id == "spiral_harvester":
        mission_completed = env.crystals_mined >= 8
        mission_message = f"Harvested {env.crystals_mined}/16 crystals via spiral pattern!" if mission_completed else "Mine at least 8 crystals using spiral traversal."
    elif mission_id == "crater_labyrinth":
        mission_completed = env.crystals_mined >= 2 or (env.rover_x >= 14 and env.rover_z >= 14)
        mission_message = "Navigated through the basalt labyrinth!" if mission_completed else "Navigate around basalt boulders to collect crystals."
    elif mission_id == "crystal_sorter":
        mission_completed = env.crystals_mined >= 5
        mission_message = f"Surveyed and harvested {env.crystals_mined} planetary crystals!" if mission_completed else "Collect at least 5 colored crystals."
    else:
        mission_completed = env.crystals_mined >= 1 or len(env.actions) > 5
        mission_message = f"Planetary expedition active: {env.crystals_mined} crystals gathered."

    result = {
        "success": error_str is None,
        "missionId": mission_id,
        "world": {
            "width": env.width,
            "height": env.height,
            "seed": seed,
            "initialCrystals": initial_crystals,
            "obstacles": obstacles,
        },
        "initialRover": {
            "x": env.width // 2 if mission_id not in ("first_landing", "crater_labyrinth") else (2 if mission_id == "first_landing" else 1),
            "z": env.height // 2 if mission_id not in ("first_landing", "crater_labyrinth") else (8 if mission_id == "first_landing" else 1),
            "heading": 'EAST' if mission_id in ("first_landing", "crater_labyrinth") else 'NORTH',
        },
        "actions": env.actions,
        "logs": output_logs,
        "finalCrystals": env.crystals_mined,
        "finalEnergy": round(env.rover_energy, 1),
        "stepsCount": len(env.actions),
        "missionCompleted": mission_completed,
        "missionMessage": mission_message,
        "error": error_str
    }
    return result


def main():
    if len(sys.argv) > 1 and sys.argv[1] == '--test':
        sample_code = """
for i in range(5):
    rover.forward()
    if rover.detect_crystal():
        rover.mine()
        print(f"Mined crystal at {rover.get_pos()}!")
    rover.turn_right()
"""
        res = run_simulation(sample_code, "spiral_harvester")
        print(json.dumps(res, indent=2))
        return

    try:
        raw_input = sys.stdin.read()
        if not raw_input.strip():
            print(json.dumps({"success": False, "error": "Empty input payload"}))
            return

        payload = json.loads(raw_input)
        code = payload.get("code", "")
        mission_id = payload.get("missionId", "free_explore")
        seed = payload.get("seed", 42)
        custom_config = payload.get("customConfig", None)

        sim_result = run_simulation(code, mission_id=mission_id, seed=seed, custom_config=custom_config)
        print("__PYROVER_SIMULATION_RESULT__:" + json.dumps(sim_result))
    except Exception as e:
        print("__PYROVER_SIMULATION_RESULT__:" + json.dumps({
            "success": False,
            "error": f"Internal Runner Error: {str(e)}"
        }))

if __name__ == '__main__':
    main()
