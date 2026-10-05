package com.johnboniello.runwalktimer;

import org.json.JSONException;
import org.json.JSONObject;

/** Workout settings. All durations are in whole seconds. */
final class Settings {
    int run = 120;
    int walk = 30;
    boolean eatOn = true;
    int eatEvery = 2700;
    int eatWin = 300;
    boolean drinkOn = true;
    int drinkEvery = 900;

    static Settings parse(String json) {
        Settings s = new Settings();
        if (json == null) return s;
        try {
            JSONObject o = new JSONObject(json);
            s.run = Math.max(5, o.optInt("run", s.run));
            s.walk = Math.max(5, o.optInt("walk", s.walk));
            s.eatOn = o.optBoolean("eatOn", s.eatOn);
            s.eatEvery = Math.max(60, o.optInt("eatEvery", s.eatEvery));
            s.eatWin = Math.max(10, o.optInt("eatWin", s.eatWin));
            s.drinkOn = o.optBoolean("drinkOn", s.drinkOn);
            s.drinkEvery = Math.max(60, o.optInt("drinkEvery", s.drinkEvery));
        } catch (JSONException ignored) {
        }
        return s;
    }

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("run", run).put("walk", walk)
                    .put("eatOn", eatOn).put("eatEvery", eatEvery).put("eatWin", eatWin)
                    .put("drinkOn", drinkOn).put("drinkEvery", drinkEvery);
        } catch (JSONException ignored) {
        }
        return o;
    }
}
