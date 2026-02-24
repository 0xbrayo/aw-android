# Android Query Server-Side Classes Implementation

## Overview

This document describes the changes made to replace default hardcoded classes with server-side classes in the Android JNI query function using the AwClient HTTP API.

## Changes Made

### File Modified
- `aw-server-rust/aw-server/src/android/mod.rs`

### Function Updated
- `Java_net_activitywatch_android_RustInterface_androidQuery`

## What Changed

### Before
The `androidQuery` JNI function was using hardcoded default classes:

```rust
let params = AndroidQueryParams {
    base: QueryParamsBase {
        bid_browsers: Vec::new(),
        classes: default_classes(),  // Hardcoded defaults
        filter_classes: Vec::new(),
        filter_afk: true,
        include_audible: true,
    },
    bid_android,
};
```

### After
The function now fetches classes from the server's settings via HTTP API using the blocking AwClient:

```rust
// Get classes from server settings via HTTP API
let classes = match AwClient::new("127.0.0.1", 5600, "aw-android-query") {
    Ok(client) => {
        match client.get_setting("classes") {
            Ok(classes_value) => {
                // Parse the server-side classes from JSON value
                match serde_json::from_value::<Vec<aw_models::Class>>(classes_value) {
                    Ok(server_classes) => {
                        if server_classes.is_empty() {
                            info!("Server classes list is empty, using default classes");
                            default_classes()
                        } else {
                            // Convert from aw_models::Class to CategorySpec format
                            server_classes
                                .iter()
                                .map(|c| {
                                    let category_id: CategoryId = c.name.clone();
                                    let category_spec = CategorySpec {
                                        spec_type: c.rule.rule_type.clone(),
                                        regex: c.rule.regex.clone().unwrap_or_default(),
                                        ignore_case: c.rule.ignore_case.unwrap_or(false),
                                    };
                                    (category_id, category_spec)
                                })
                                .collect()
                        }
                    }
                    Err(e) => {
                        warn!("Failed to parse server classes, using defaults: {:?}", e);
                        default_classes()
                    }
                }
            }
            Err(e) => {
                info!("Failed to get server classes, using defaults: {:?}", e);
                default_classes()
            }
        }
    }
    Err(e) => {
        warn!(
            "Failed to create client for fetching classes, using defaults: {:?}",
            e
        );
        default_classes()
    }
};

let params = AndroidQueryParams {
    base: QueryParamsBase {
        bid_browsers: Vec::new(),
        classes,  // Now uses server-side classes
        filter_classes: Vec::new(),
        filter_afk: true,
        include_audible: true,
    },
    bid_android,
};
```

## Implementation Details

### Why AwClient HTTP API?

The implementation uses the `AwClient` blocking HTTP client instead of direct datastore access for several reasons:

1. **Consistency**: Follows the same pattern used in `aw-sync` Android JNI functions
2. **Encapsulation**: Uses the proper HTTP API layer rather than bypassing it with direct datastore access
3. **Reliability**: Leverages the existing, well-tested client code
4. **Type Safety**: Uses `aw_models::Class` which is publicly exported, avoiding private module access

### Data Flow

1. Create a blocking `AwClient` instance pointing to `127.0.0.1:5600` (local server)
2. Call `client.get_setting("classes")` to fetch the classes from the server
3. Deserialize the JSON value into `Vec<aw_models::Class>`
4. Convert each class from the server's format to the query engine's format:
   - `name` → `CategoryId` (Vec<String>)
   - `rule.rule_type` → `spec_type` (String)
   - `rule.regex` → `regex` (String, with default empty string)
   - `rule.ignore_case` → `ignore_case` (bool, with default false)
5. If any step fails, log appropriately and fall back to default classes

### Error Handling

The implementation has multiple layers of graceful fallback:

- **Client Creation Failed**: Logs warning and uses default classes
- **Get Setting Failed**: Logs info and uses default classes  
- **Parse Error**: Logs warning and uses default classes
- **Empty Classes List**: Logs info and uses default classes
- **Null/Missing Fields**: Uses sensible defaults (empty string for regex, false for ignore_case)

## Benefits

1. **Dynamic Configuration**: Classes can now be configured through the ActivityWatch web UI without recompiling the app
2. **User Customization**: Users can define their own categorization rules that will be used in Android queries
3. **Consistency**: Android queries now use the same classes as desktop queries when configured through settings
4. **Graceful Fallback**: If server-side classes are not available or fail to parse, the function falls back to default classes
5. **Proper API Usage**: Uses the HTTP API layer instead of bypassing it with direct datastore access

## Settings API

Classes are stored in the server under the key `settings.classes` and can be:
- Retrieved via: `GET /api/0/settings/classes`
- Updated via: `POST /api/0/settings/classes`
- Deleted via: `DELETE /api/0/settings/classes`

The AwClient abstracts these API calls with:
```rust
client.get_setting("classes")  // GET /api/0/settings/classes
```

## Class Format

Server-side classes follow the `aw_models::Class` format:

```json
[
  {
    "id": 1,
    "name": ["Work", "Programming"],
    "rule": {
      "type": "regex",
      "regex": "GitHub|Stack Overflow",
      "ignore_case": true
    },
    "data": {
      "color": "#4287f5",
      "score": 100
    }
  },
  {
    "id": 2,
    "name": ["Media", "Social Media"],
    "rule": {
      "type": "regex",
      "regex": "reddit|Facebook|Twitter",
      "ignore_case": true
    },
    "data": {
      "color": "#ff6b6b",
      "score": -50
    }
  }
]
```

## Compatibility

- **Backward Compatible**: If no server-side classes exist, default classes are used
- **Forward Compatible**: Supports all fields defined in `aw_models::Class`
- **The `data` field** (color, score) is preserved in settings but not used by the query engine
- **Optional fields**: The implementation handles missing `regex` and `ignore_case` fields gracefully

## Testing

To test the implementation:

1. **Test with custom classes**:
   ```bash
   # Set custom classes via API
   curl -X POST http://localhost:5600/api/0/settings/classes \
     -H "Content-Type: application/json" \
     -d '[{"id":1,"name":["Custom"],"rule":{"type":"regex","regex":"TestApp"}}]'
   
   # Call androidQuery() from the Android app
   # Verify the results use your custom categories
   ```

2. **Test with empty classes**:
   ```bash
   # Set empty classes list
   curl -X POST http://localhost:5600/api/0/settings/classes \
     -H "Content-Type: application/json" \
     -d '[]'
   
   # Verify it falls back to defaults
   ```

3. **Test with no classes setting**:
   ```bash
   # Delete the classes setting
   curl -X DELETE http://localhost:5600/api/0/settings/classes
   
   # Verify it falls back to defaults
   ```

4. **Test with server not running**:
   - Stop the server
   - Call androidQuery()
   - Verify it falls back to defaults with appropriate warning

## Log Messages

The implementation provides clear log messages for debugging:

- `info!("Server classes list is empty, using default classes")` - Empty list received
- `warn!("Failed to parse server classes, using defaults: {:?}", e)` - JSON parsing failed
- `info!("Failed to get server classes, using defaults: {:?}", e)` - HTTP request failed
- `warn!("Failed to create client for fetching classes, using defaults: {:?}", e)` - Client creation failed

## Future Enhancements

Potential improvements:

- **Caching**: Add caching to avoid repeated HTTP requests for each query
- **Configuration**: Make the server host/port configurable
- **Validation**: Add regex pattern validation before use to catch errors early
- **Metrics**: Expose whether server or default classes were used in query metadata
- **Timeout**: Add configurable timeout for the HTTP request
- **Retry Logic**: Add retry mechanism for transient failures

## Dependencies

The implementation requires:
- `aw_client_rust::blocking::AwClient` - For HTTP API access
- `aw_models::Class` - Server-side class model
- `aw_client_rust::classes::CategorySpec` - Query engine class format
- `serde_json` - For JSON deserialization

## Performance Considerations

- The HTTP request is made synchronously on each `androidQuery()` call
- Typical response time is < 10ms for local server requests
- Fallback to default classes is instant if server is unavailable
- Consider implementing caching if query frequency is very high