<div dir="rtl" align="right" style="direction: rtl; text-align: right;">

# קונג פו שחמט (Kung Fu Chess)

משחק שחמט בזמן אמת **בלי תורות**: שני הצדדים יכולים לזוז בו-זמנית, וכל מהלך אורך **זמן אמיתי** (מדומה, במילישניות) במקום להתבצע מיידית. כלי שנשלח לתנועה נמצא "באוויר" למשך זמן שתלוי במרחק שהוא עובר — מה שפותח פתח למצבי גזע (Race Conditions), תפיסות באוויר, ו"קפיצות" הגנתיות שאין להן מקבילה בשחמט הקלאסי.

המשחק רץ כתוכנית קונסולה: קורא פריסת לוח ורשימת פקודות מ-`stdin`, ומדפיס תוצאות ל-`stdout`.

> For the English version, see [`README.md`](README.md). לתיעוד טכני מעמיק, קובץ-אחר-קובץ, ראו [`ARCHITECTURE.he.md`](ARCHITECTURE.he.md).

---

## ארכיטקטורה

הפרויקט בנוי לפי **Clean Architecture**, ומחולק לארבע שכבות מנותקות לחלוטין, פלוס שכבת adapters חיצונית לקלט/פלט. כל שכבה מכירה רק את השכבות "הפנימיות" ממנה — התלויות תמיד מצביעות פנימה, לכיוון ה-model:

```
main/java/org/example/
├── Main.java              # Composition root: מרכיב את כל התלויות ידנית
├── model/                 # ישויות דומיין טהורות — אפס תלויות
│   ├── Board.java
│   ├── Piece.java
│   └── Position.java
├── rules/                 # חוקים עסקיים ואימות מהלכים — תלוי רק ב-model
│   ├── MoveValidationService.java
│   ├── PawnPromotionService.java
│   ├── AirCaptureService.java
│   ├── ActiveMoveQuery.java
│   └── MoveValidationPort.java
├── engine/                 # ניהול מצב בזמן-אמת וסנכרון
│   ├── MovementEngine.java
│   ├── ActiveMove.java
│   └── EnginePort.java
├── controller/             # ניתוב פקודות / תיאום האפליקציה
│   ├── GameController.java
│   └── InteractionHandler.java
└── adapters/                # קלט/פלט (קונסולה)
    ├── BoardParser.java
    ├── BoardPresenter.java
    ├── CommandLineAdapter.java
    └── CommandType.java
```

| שכבה | אחריות | מותר לה להיות תלויה ב- |
|---|---|---|
| **Model** | אובייקטי דומיין טהורים (`Board`, `Piece`, `Position`) ללא כל התנהגות הקשורה לתזמון, קלט/פלט או חוקיות. | *כלום* |
| **Rules** | חוקים עסקיים ואימות מהלכים — בדיקות חוקיות, הכתרת רגלי, לוגיקת תפיסה בהתנגשות. | Model |
| **Engine** | ניהול מצב בזמן-אמת: שעון המשחק, מהלכים ש"באוויר", וסנכרון בין פעולות סימולטניות. | Model, Rules |
| **Controller** | נקודת הכניסה של האפליקציה — מנתב פקודות לשכבות engine ו-rules דרך ממשקים (ports), ולעולם לא דרך מחלקות קונקרטיות. | Model, Rules (דרך ports), Engine (דרך ports) |
| **Adapters** | קלט/פלט של הקונסולה: פענוח קלט, הדפסת הלוח. נשמרת לגמרי מחוץ לארבע השכבות המרכזיות. | הכל |

ההפרדה הזו חשובה מכמה סיבות מעשיות:

- **יכולת בדיקה (Testability).** את שכבת ה-`rules` אפשר לבדוק ביחידה עם אובייקטי model פשוטים ופורט מדומה (fake) בן שורה אחת — בלי engine, בלי controller, בלי קלט/פלט. גם את שכבת ה-`engine` אפשר לבדוק עם לוח אמיתי וללא controller כלל.
- **אין צימוד נסתר.** אף אחת מהשכבות `model`, `rules`, `engine` או `controller` לא מכילה קריאת `System.out` בודדת או כל ידיעה על אופן קבלת הקלט. כל הקלט/פלט של הקונסולה חי בשכבת ה-`adapters` בלבד.
- **עיצוב המאפשר החלפת ממשק משתמש.** מכיוון ש-`GameController` תלוי רק ב-`EnginePort` וב-`MoveValidationPort` — ולא במחלקות הקונקרטיות `MovementEngine` או `MoveValidationService` — ניתן להחליף את מתאם הקונסולה בממשק גרפי, בשרת WebSocket, או בלקוח AI, מבלי לגעת בשורת קוד אחת של לוגיקת המשחק.

---

## תכונות

- **תנועה בזמן-אמת, ללא תורות** — לכל מהלך יש משך זמן התלוי במרחק שהכלי עובר (`MOVE_DURATION_PER_SQUARE`), והלוח מתעדכן רק כשמהלך אכן מגיע ליעדו בפועל.
- **ניהול מצבי גזע (Race Conditions)** — כאשר שני כלים בצבעים שונים נשלחים לאותה משבצת באותו טיק, המנוע פותר את ההתנגשות באופן דטרמיניסטי במקום לפגוע בשלמות מצב הלוח: כל משבצות המוצא מנוקות קודם, ואז היעדים נפתרים כקבוצה — כך שהמהלך שנוסף ראשון מנצח את המשבצת, והמפסיד נתפס.
- **תפיסות באוויר (Air Captures) דרך קפיצות** — כלי יכול "לקפוץ" במקומו כדי לשמור על משבצת, ולתפוס מהלך יריב שמגיע אליה בזמן שהקפיצה עדיין פעילה.
- **פיתוח מונחה בדיקות (TDD)** — לפרויקט חבילת בדיקות JUnit נרחבת (11 מחלקות בדיקה מבוססות-איטרציה, המכסות פענוח קלט, תנועה, חסימות, חוקי רגלי, תזמון, קפיצות ותנאי סיום משחק) שנכתבה והורחבה במקביל למימוש.
- **עיצוב עם היפוך תלויות (Dependency Inversion)** — שכבת ה-`rules` מגדירה בעצמה את הממשקים הצרים שהיא זקוקה להם מהשכבות החיצוניות (`ActiveMoveQuery`, `MoveValidationPort`) במקום להיות תלויה בהן ישירות, כך שגרף התלויות תמיד מצביע לכיוון הדומיין, ולא ממנו והלאה.

---

## פקודות המשחק

התוכנית קוראת מ-`stdin` קטע לוח וקטע פקודות:

```
Board:
<שורת לוח 1>
<שורת לוח 2>
...
Commands:
<פקודה 1>
<פקודה 2>
...
```

שורות הלוח משתמשות בטוקנים בסגנון `wK`/`bK` (`w`/`b` לצבע, `K/Q/R/N/B/P` לסוג הכלי), ו-`.` למשבצת ריקה.

| פקודה | תיאור |
|---|---|
| `print board` | מדפיס את מצב הלוח הנוכחי |
| `click X Y` | קליק בקואורדינטות **פיקסלים** `(X, Y)` — בוחר כלי, או מנסה לבצע מהלך אם כבר נבחר כלי |
| `jump X Y` | מבצע קפיצה הגנתית בקואורדינטות פיקסלים `(X, Y)` |
| `wait MS` | מקדם את שעון המשחק ב-`MS` מילישניות |

`X`/`Y` הן קואורדינטות פיקסלים על רשת מדומה, שמומרות לתאי לוח לפי `row = Y / Board.CELL_SIZE` ו-`col = X / Board.CELL_SIZE` (`CELL_SIZE = 100`).

דוגמה:

```
Board:
wK . . . . . . .
. . . . . . . .
. . . . . . . .
. . . . . . . .
. . . . . . . .
. . . . . . . .
. . . . . . . .
. . . . . . . bK
Commands:
click 50 50
click 150 50
wait 1000
print board
```

---

## בנייה והרצה

לפרויקט אין כלי בנייה (ללא Maven/Gradle) — הוא מתקומפל ישירות מהקוד תחת `main/java`, כאשר `JUnit`/`Hamcrest` (תחת `lib/`) נדרשים רק להרצת חבילת הבדיקות תחת `test/java`.

```bash
# קומפילציה
javac -d out $(find main/java -name '*.java')

# הרצה
java -cp out org.example.Main < input.txt

# קומפילציה והרצה של הבדיקות
javac -d out -cp "lib/junit-4.13.1.jar:lib/hamcrest-core-1.3.jar:out" $(find test/java -name '*.java')
java -cp "lib/junit-4.13.1.jar:lib/hamcrest-core-1.3.jar:out" org.junit.runner.JUnitCore org.example.Iteration1_BoardParsingTest
```

### הערה טכנית: `TestGameControllerFactory`

`GameController` משתמש ב-**הזרקת תלויות טהורה דרך constructor** — הוא אף פעם לא בונה בעצמו את שותפיו, כך שכל בדיקה שצריכה משחק "מורכב" במלואו חייבת לבנות את גרף האובייקטים ידנית: `MovementEngine`, `MoveValidationService` הקשור אליו, `InteractionHandler` הקשור לשניהם, ולבסוף את `GameController` עצמו.

כדי להימנע מחזרה על החיווט הזה בכל נקודת בדיקה, הקובץ `test/java/org/example/TestGameControllerFactory.java` מספק שיטת factory סטטית יחידה:

```java
GameController gc = TestGameControllerFactory.create(board);
```

זהו כלי עזר **לבדיקות בלבד** — הוא מבצע בדיוק את אותו חיווט ידני ש-composition root (`Main.java`) מבצע בסביבת הפרודקשן, רק ארוז לשימוש חוזר לאורך חבילת הבדיקות. קוד הפרודקשן לעולם לא קורא לו.

---

## רישיון

הריפוזיטורי הזה אינו כולל כרגע קובץ רישיון.

</div>
