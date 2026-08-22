# weather-forecast

description: Weather forecasts without an API key, and how to work out which place was meant.
triggers: weather, forecast, temperature, rain, wind, celsius, meteo, snow, sunny, cloudy, прогноза, температура, дъжд, вятър, градуси, метео, синоптична, вали, валежи, сняг, облачно, слънчево, застудяване, затопляне

Two free services, no key, no account. Everything below was run on 2026-08-23 and the responses
are what they actually returned.

## Which place was meant

Work this out before fetching anything, and **say which place you used and how you decided**. A
forecast for the wrong city looks exactly like a forecast for the right one.

**Named in the request** — use it. "прогноза за Париж" is Paris, whatever the connection says.

**Not named** — the connection is the best guess available:

```
https://ipinfo.io/json
```

returns `city`, `loc` as "lat,lon", `timezone` and `org` — enough to skip geocoding entirely.
`http://ip-api.com/json/` returns the same with `lat`/`lon` split out; `https://ipapi.co/json/`
also works. Any one is enough; do not call all three.

**This is where the connection comes out, not where the person is.** On a VPN it names the exit
node — a forecast for Frankfurt when they are sitting in Sofia. So name the city in the answer
and let them correct it in one word. Never present a guessed location as if it had been asked for.

The `org` field usually gives it away: a consumer ISP is probably home, a hosting provider is
probably a VPN. Worth a mention when it looks like the latter.

## Turning a name into coordinates

```
https://geocoding-api.open-meteo.com/v1/search?name=Paris&count=1&language=bg
```

**The query has to be Latin.** `name=Париж` returns nothing at all — no error, no results, just an
empty response, which is easy to mistake for "no such place". `name=Paris` works. `language=bg`
only changes the language of the *answer*: it comes back as `Париж`, with `latitude`, `longitude`,
`timezone`, `country_code` and `population`.

So transliterate before asking: Пловдив → Plovdiv, София → Sofia, Париж → Paris.

`count` above 1 is worth using when a name is ambiguous — there are many Springfields, and several
Парижа. Sort by `population` or ask, rather than silently taking the first.

## The forecast

```
https://api.open-meteo.com/v1/forecast
  ?latitude=42.6975&longitude=23.3241
  &daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,wind_speed_10m_max
  &timezone=auto
  &forecast_days=3
```

`timezone=auto` makes the day boundaries local to that place, which is what a person means by
"tomorrow". `forecast_days` goes up to 16. For an hourly picture use `hourly=` with the same field
names; `current=` gives the present reading.

The response has `daily.time` as ISO dates and one array per field, index-aligned. Field names are
in `daily_units` — read them rather than assuming °C and km/h, because the API can be asked for
Fahrenheit and mph and will say so there.

### weather_code is a WMO number, not a word

The codes are worth knowing because nothing in the response spells them out:

| | |
|---|---|
| 0 | clear |
| 1–3 | mainly clear → overcast |
| 45, 48 | fog |
| 51–57 | drizzle |
| 61–67 | rain |
| 71–77 | snow |
| 80–82 | rain showers |
| 85, 86 | snow showers |
| 95–99 | thunderstorm |

A code with no entry here is not "clear" — say the number rather than guessing at it.

## Traps

- **An elevation mismatch is not an error.** Asking for 42.6975 gets 42.6875 back: the model runs
  on a grid and answers from the nearest cell. The returned `elevation` can be a few hundred
  metres off in mountains, which changes the temperature. Report the place, not the grid point.
- **Zero precipitation and no precipitation field are different things.** `0.00` means it was
  computed; a missing field means it was never requested.
- **Historical dates are a different endpoint** (`archive-api.open-meteo.com`). The forecast API
  silently ignores past dates rather than refusing.
- **Both services are unauthenticated and rate-limited.** One call per question is enough; if a
  batch is needed, `latitude`/`longitude` accept comma-separated lists in a single request.
- Reaching them at all needs `http_get`, so a workspace shell scope is irrelevant here, but no
  network means no answer — say so rather than producing a plausible one.

## What to do with it

Not decided here. Ask what they want if it is not obvious from the question, and answer in the
shape they asked for.

## A word that is deliberately not a trigger

`времето` means both the weather and the time, and the matcher treats it as a form of `време`, so
it pulled this skill into "колко време отнема този билд". It is out of the trigger list for that
reason. "Какво е времето навън" therefore reaches nothing on its own — but the skill list is in
front of the model either way, so a miss costs a decision, while a false match costs a page of
irrelevant instructions on every question containing the word.
