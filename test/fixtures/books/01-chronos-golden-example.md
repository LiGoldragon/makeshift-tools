<!-- to-the-living:start -->
Presentation.{ «Chronos, the golden example» }

# Chronos, the golden example

```
+------------------+
| Ethos            |
+------------------+
         |
         | specifies
         v
+------------------+
| the Nexus        |
+------------------+
         |
         | is the build of
         v
+------------------+
| Chronos          |
| golden example   |
+------------------+
         |
         | guides
         v
+------------------+
| Flow             |
+------------------+
         |
         | then guides
         v
+------------------+
| other nexuses    |
+------------------+
```

### Proposal 1: Chronos, a new module · psyche-context › vision/chronos.md

File: psyche-context › vision/chronos.md (new)

+ ---
+ description: Chronos, the Nexus that tells astrological time, is designed or judged against what the living wants it to be.
+ dependencies: [vision-nexus, vision-ethos]
+ ---
+
+ ## Chronos tells astrological time
+
+ Chronos tells astrological time for the present moment, or for a given time and location.
+ It tells it in a purely solar or a purely lunar format: the solar format is the Sun's arc; the lunar format is the lunation, the Moon's arc less the Sun's.
+ It may tell more: the slower points, the lunar nodes, the apogee and the aphelion.
+ The nodes and the apogee are the true points; the aphelion is the Sun's place when it is farthest from the Earth.
+ The location is the one a request carries, else the standing location set over the meta socket.
+ Chronos reckons from JPL's DE440 ephemeris, for the years 1550 to 2650.
+
+ ## Tropical, on the ecliptic
+
+ Everything is tropical, the nakṣatras included: Aśvinī begins at 0° Aries.
+ Everything is reckoned around the ecliptic of the Sun.
+
+ ## Positions are arcs
+
+ Chronos answers arcs. Sign and nakṣatra are not kept: they are derived from an arc, absolute or relative, by the trait methods of the Library, which any client calls on an arc it holds.
+ A lunation of 31° between the Sun and the Moon reads as Taurus.
+
+ ## The golden example
+
+ Chronos is the golden example: a simple, idiomatic Nexus with a full ethos specification, built on Ethos and the Nexus.
+ Flow is implemented from it, then the other nexuses.

```
+------------------------+
| the Sun at 120°        |
| the Moon at 151°       |
+------------------------+
            |
            | subtract into
            v
+------------------------+
| the lunation, 31°      |
+------------------------+
       |            |
       | reads as   | reads as
       v            v
+-------------+ +--------------+
| Taurus, 1°  | | Kṛttikā,     |
|             | | 4°20′        |
+-------------+ +--------------+
```

Ruling: land, amend, or reject.

### Proposal 2: The shared Library · signal-chronos › ethos/library.ethos

File: signal-chronos › ethos/library.ethos (new)

```ethos
+ Library                     ; what the parts share
+ []                          ; imports: none
+ [ Instant.Integer           ; types: Unix seconds
+   Arc.num_rational:Rational64 ; degrees
+   Location.{ Latitude.Arc   ;   a place on Earth
+              Longitude.Arc }
+   Moment.{ Instant          ;   when and where
+            Location }
+   Body.[ Sun                ;   a light, or a
+          Moon               ;   slower point
+          NorthNode
+          SouthNode
+          Apogee             ;   the Moon's
+          Aphelion ]         ;   the Sun's
+   Reading.[ Absolute.Body   ;   what is asked:
+             Relative.{ Body ;   the second less
+                        Body } ] ; the first
+   Placement.{ Reading       ;   its answer
+               Arc }
+   Step.[ BySign             ;   what counts as
+          ByNaksatra         ;   a change
+          ByDegree ]
+   SubscriptionId.Integer    ;   one subscription
+   Sign.[ Aries              ;   derived, never
+          Taurus             ;   kept or told
+          Gemini
+          Cancer
+          Leo
+          Virgo
+          Libra
+          Scorpio
+          Sagittarius
+          Capricorn
+          Aquarius
+          Pisces ]
+   Naksatra.[ Asvini         ;   derived: 27 from
+              Bharani        ;   0° Aries
+              Krttika
+              Rohini
+              Mrgasira
+              Ardra
+              Punarvasu
+              Pusya
+              Aslesa
+              Magha
+              PurvaPhalguni
+              UttaraPhalguni
+              Hasta
+              Citra
+              Svati
+              Visakha
+              Anuradha
+              Jyestha
+              Mula
+              PurvaAsadha
+              UttaraAsadha
+              Sravana
+              Dhanistha
+              Satabhisaj
+              PurvaBhadrapada
+              UttaraBhadrapada
+              Revati ]
+   InSign.{ Sign             ;   the arc gone
+            Arc }            ;   into the sign
+   InNaksatra.{ Naksatra     ;   the arc gone
+                Arc } ]      ;   into it
+ [ Zodiacal.[ sign.[ InSign ] ; traits: derived
+              naksatra.[ InNaksatra ] ] ]
+ [ Arc.[ Zodiacal ] ]        ; associations
```

Ruling: land, amend, or reject.

### Proposal 3: What Chronos says · signal-chronos › ethos/signal.ethos

File: signal-chronos › ethos/signal.ethos (new)

```ethos
+ Signal                      ; ordinary socket
+ [ chronos:[ Instant         ; imports
+             Moment
+             Reading
+             Placement
+             Step
+             SubscriptionId ] ]
+ [ Tell.{ Vector<Reading>    ; queries: arcs
+          Option<Moment> }   ;   none: now, here
+   Subscribe.{ Vector<Reading>
+               Step }        ;   then each change
+   Unsubscribe.SubscriptionId ]
+ [ Told.Vector<Placement>    ; responses
+   Subscribed.{ SubscriptionId
+                Vector<Placement> } ; arcs now
+   Changed.{ SubscriptionId  ;   at each step
+             Vector<Placement> }
+   Unsubscribed.SubscriptionId
+   Refused.[ NoLocation      ;   typed refusals
+             BeyondEphemeris.Instant
+             NoSuchSubscription.SubscriptionId ] ]
+ []                          ; types
```

```
+----------------------+
| the client           |
+----------------------+
           |
           | sends Subscribe to
           v
+----------------------+
| Signal               |
+----------------------+
           |
           | intends Open for
           v
+----------------------+
| Operation            |
+----------------------+
           |
           | hands the Subscription to
           v
+----------------------+
| Memory               |
+----------------------+
           |
           | answers Succeeded to
           v
+----------------------+
| Operation            |
+----------------------+
           |
           | answers Opened to
           v
+----------------------+
| Signal               |
+----------------------+
           |
           | answers Subscribed to
           v
+----------------------+
| the client           |
+----------------------+
```

Ruling: land, amend, or reject.

### Proposal 4: What Chronos does · chronos › ethos/operation.ethos

File: chronos › ethos/operation.ethos (new)

```ethos
+ Operation                   ; one per effect
+ [ chronos:[ Instant         ; imports
+             Location
+             Moment
+             Reading
+             Placement
+             Step
+             SubscriptionId ] ]
+ [ Reckon.{ Vector<Reading>  ; operations
+            Option<Moment> } ;   compute the arcs
+   Open.{ Vector<Reading>    ;   a subscription
+          Step }
+   Wake.SubscriptionId       ;   at a crossing
+   Close.SubscriptionId      ;   end one
+   Store.Location ]          ;   standing place
+ [ Reckoned.Vector<Placement> ; outcomes
+   Opened.{ SubscriptionId   ;   and the arcs now
+            Vector<Placement> }
+   Woke.{ SubscriptionId     ;   the new arcs
+          Vector<Placement> }
+   Closed.SubscriptionId
+   Stored.Location
+   Failed.[ NoLocation       ;   each refusal
+            BeyondEphemeris.Instant
+            NoSuchSubscription.SubscriptionId
+            StoreRefused ] ]
+ []                          ; types
```

```
+----------------------+
| Operation            |
+----------------------+
           |
           | reckons the next
           | crossing, sets
           v
+----------------------+
| the clock            |
+----------------------+
           |
           | gives Wake to
           v
+----------------------+
| Operation            |
+----------------------+
           |
           | answers Woke to
           v
+----------------------+
| Signal               |
+----------------------+
           |
           | sends Changed to
           v
+----------------------+
| the client           |
+----------------------+
```

Ruling: land, amend, or reject.

### Proposal 5: What Chronos remembers · chronos › ethos/memory.ethos

File: chronos › ethos/memory.ethos (new)

```ethos
+ Memory                      ; what it keeps
+ [ chronos:[ Location        ; imports
+             Reading
+             Step
+             SubscriptionId ] ]
+ [ Standing.Location   ; records: default place
+   Subscription.{ SubscriptionId ; working state,
+                  Vector<Reading> ; emptied at
+                  Step } ]  ; start
```

Ruling: land, amend, or reject.

### Proposal 6: What the owner says · meta-signal-chronos › ethos/signal.ethos

File: meta-signal-chronos › ethos/signal.ethos (new)

```ethos
+ Signal                      ; meta socket
+ [ chronos:Location ]        ; imports
+ [ Configure.Location ]      ; queries: the place
+ [ Configured.Location       ; responses
+   Refused.[ OutOfRange.Location ] ]
+ []                          ; types
```

```
+--------------------+ +--------------------+
| meta-signal-       | | chronos            |
| chronos            | | Operation, Memory  |
+--------------------+ +--------------------+
          |                      |
          | imports from         | imports from
          v                      v
+-------------------------------------------+
| signal-chronos                            |
| Library, Signal                           |
+-------------------------------------------+
```

Ruling: land, amend, or reject.

### Proposal 7: Archive the Chronos records · flows › 984e88/vision/chronos.md

**Archive** flows › 984e88/vision/chronos.md, whole, into flows › 984e88/vision/archive-chronos.md; Proposal 1 crystallizes its three records.

**Destroy**, before it moves, the sentence of its first record that orders the rewrite; what it says of Chronos lives on in Proposal 1:

- Let's rewrite it in idiomatic Nexus architecture and ethos: full specification, just a simple thing, right?

Ruling: land, amend, or reject.

### Proposal 8: Archive the arc record · flows › d68c82/vision/chronos.md

**Archive** flows › d68c82/vision/chronos.md, whole, into flows › d68c82/vision/archive-chronos.md; Proposal 1 crystallizes its one record, sign and nakṣatra obtained from an arc.

Ruling: land, amend, or reject.
<!-- to-the-living:end -->
