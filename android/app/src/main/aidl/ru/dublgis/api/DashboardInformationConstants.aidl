package ru.dublgis.api;


interface DashboardInformationConstants {
    /////////////////////////////////////////////////////////////////////////
    // JSON field names
    /////////////////////////////////////////////////////////////////////////


    // 2GIS 7.16+
    // String. Possible values: "" (navigation not active) or one of the NAVIGATION_MODE_* constants below
    const String ACTIVE_NAVIGATION_MODE = "activeNavigationMode";
    // String. Human-readable.
    const String ARRIVAL_TIME = "arrivalTime";
    // String. Nearest obstacle description. This is usually used in pedestrian mode only.
    // Don't use for car navigation for now.
    const String BARRIER_DESCRIPTION = "barrierDescription";
    // String. Icon codename can be used to find appropriate image in client's assets
    const String BARRIER_ICON = "barrierIcon";
    // String. Current/nearest maneuver explanation
    // Note: can be empty in NAVIGATION_MODE_FREE_ROAM mode.
    const String MANEUVER_DESCRIPTION = "maneuverDescription";
    // String. Human-readable.
    // Note: can be empty in NAVIGATION_MODE_FREE_ROAM mode.
    const String MANEUVER_DISTANCE = "maneuverDistance";
    // String. Icon codename can be used to find appropriate image in client's assets
    // Note: can be empty in NAVIGATION_MODE_FREE_ROAM mode
    const String MANEUVER_ICON = "maneuverIcon";
    // Int. This is a percent of route completion. If value is outside of [0..100]
    // the percent is unknown and the value must be ignored.
    const String PROGRESS = "progress";
    // String. Human-readable.
    const String REMAINING_TIME = "remainingTime";
    // String. Human-readable.
    const String TOTAL_DISTANCE = "totalDistance";

    // 2GIS 7.18+
    // Double. Current speed limit in meters per second.
    // If speedLimit <= 0 the speed limit is not supported or undefined at the moment.
    const String SPEED_LIMIT = "speedLimit";
    // Boolean. True means that we have detected that the vehicle is moving faster than the speed limit.
    // (If speedLimit <= 0.0 this value should always be false.)
    const String EXCEEDING_MAX_SPEED_LIMIT = "exceedingMaxSpeedLimit";
    // Boolean. True means that we have lost our location (or don't know it with enough precision)
    const String BAD_LOCATION = "badLocation";
    // String. Empty string - no known camera nearby
    // Any other value - new type of camera (contact 2GIS for update)
    const String TRAFFIC_CAMERA_TYPE = "trafficCameraType";
    // String. Any other value - new subtype (contact 2GIS for update, use normal camera image)
    const String TRAFFIC_CAMERA_SUBTYPE = "trafficCameraSubtype";
    // Float. Counts from 0 to 100 as we approach the camera.
    // Values outside of the range are meaningless.
    // If trafficCameraType is empty then the percent is meaningless.
    const String TRAFFIC_CAMERA_DISTANCE_PERCENT = "trafficCameraDistancePercent";

    // 2GIS 7.21.7 headunitts / 7.22.0+
    // Integer (values <= 0 are "invalid")
    const String JAM_INFO_DURATION_MINUTES = "jamInfoDuration";
    // Integer (values <= 0 are "invalid")
    const String JAM_INFO_LENGTH_METERS = "jamInfoLengthMeters";

    // 2GIS 7.24.95+
    // See TRAFFIC_LIGHT_COLOR_... constants.
    // For unknown value: hide traffic light indicator.
    const String TRAFFIC_LIGHT_COLOR = "trafficLightColor";
    // See TRAFFIC_LIGHT_ARROW_... constants.
    // For unknown value: hide traffic light indicator (empty value is valid and means traffic light without arrow).
    // NOTE: This value is currently not set when countdown is available, even if the traffic light has an arrow.
    const String TRAFFIC_LIGHT_ARROW = "trafficLightArrow";
    // Countdown to next light in seconds, integer, >= 0.
    // For negative value: hide traffic light indicator.
    const String TRAFFIC_LIGHT_COUNTDOWN = "trafficLightCountdown";


    /////////////////////////////////////////////////////////////////////////
    // Values of various fields
    /////////////////////////////////////////////////////////////////////////

    // Values of ACTIVE_NAVIGATION_MODE
    // "" - navigation is not active, all other fields are invalid!
    const String NAVIGATION_MODE_CAR = "car";
    const String NAVIGATION_MODE_PEDESTRIAN = "pedestrian";
    const String NAVIGATION_MODE_BICYCLE = "bicycle";
    const String NAVIGATION_MODE_SCOOTER = "scooter";
    const String NAVIGATION_MODE_TAXI = "taxi";
    const String NAVIGATION_MODE_MOTORCYCLE = "motorcycle";
    const String NAVIGATION_MODE_PUBLIC_TRANSPORT = "publictransport";
    // Destination is unknown, only displaying road information.
    // In free roam mode maneuverDescription and maneuverIconCodename are not set.
    const String NAVIGATION_MODE_FREE_ROAM = "freeroam";
    const String NAVIGATION_MODE_CAR_STEP_BY_STEP = "carstepbystep";
    const String NAVIGATION_MODE_PEDESTRIAN_STEP_BY_STEP = "pedestrianstepbystep";
    // Navigation is active but in some other mode (should never happen but make sure to handle correctly)
    const String NAVIGATION_MODE_UNKNOWN = "unknown";

    // Values of TRAFFIC_CAMERA_TYPE
    // Checks for exceeding maximum driving speed
    const String CAMERA_TYPE_SPEED = "speed";
    // Checks for exceeding average driving speed
    const String CAMERA_TYPE_AVERAGE_SPEED = "averageSpeed";
    // Checks for errors in driving through an intersection
    const String CAMERA_TYPE_CROSSROAD = "crossroad";
    // Checks for complying road markings
    const String CAMERA_TYPE_MARKING = "marking";
    // Checks for correct parking
    const String CAMERA_TYPE_PARKING = "parking";
    // Check for driving in a correct lane
    const String CAMERA_TYPE_LANE = "lane";

    // Values of TRAFFIC_CAMERA_SUBTYPE
    // Empty or normal camera mode (the camera looks against the traffic, camera icon should be "looking down")
    const String CAMERA_SUBTYPE_BACK = "back";
    // Camera from behind (the camera looks in the direction of the traffic, camera icon should be "looking up")
    const String CAMERA_SUBTYPE_FRONT = "front";
    // Two-directional camera
    const String CAMERA_SUBTYPE_BOTH_SIDE = "both_side";

    // Values of TRAFFIC_LIGHT_COLOR
    // Any unknown value = do not draw traffic light widget
    const String TRAFFIC_LIGHT_COLOR_RED = "red";
    const String TRAFFIC_LIGHT_COLOR_GREEN = "green";
    const String TRAFFIC_LIGHT_COLOR_YELLOW = "yellow";
    // Values of TRAFFIC_LIGHT_ARROW
    const String TRAFFIC_LIGHT_ARROW_NONE = "";
    const String TRAFFIC_LIGHT_ARROW_UP = "up";
    const String TRAFFIC_LIGHT_ARROW_LEFT = "left";
    const String TRAFFIC_LIGHT_ARROW_RIGHT = "right";
}

