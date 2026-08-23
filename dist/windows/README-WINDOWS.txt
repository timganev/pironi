PIRONI PORTABLE ЗА WINDOWS 11
=============================

Не са нужни admin права, Maven или инсталирана Java. Java 25 е вътре в папката.


КАКВО ИМА В ПАПКАТА
-------------------

   pironi.bat        стартерът
   pironi.jar        програмата
   runtime\          Java 25, само за нея
   skills\           скиловете, с които идва това издание
   SOUL.example.md   образец за самоличност на агента
   USER.example.md   образец за това какво да знае за теб
   version.txt       коя версия е това

Не мести pironi.bat, pironi.jar и runtime поотделно — трябва да останат заедно.


ПЪРВО ПУСКАНЕ
-------------

1. Разархивирай ZIP-а в папка, в която имаш право да пишеш.

2. Отвори PowerShell в нея.

3. Задай API ключа само за този прозорец:

      $env:DEEPSEEK_API_KEY = "твоят-ключ"

   За постоянна променлива, без admin права — изпълни веднъж и отвори нов
   PowerShell:

      [Environment]::SetEnvironmentVariable("DEEPSEEK_API_KEY", "твоят-ключ", "User")

4. Стартирай:

      .\pironi.bat --provider deepseek --model deepseek-v4-flash

   PowerShell изисква .\ пред локален файл.

При първото пускане скиловете от skills\ се копират в %USERPROFILE%\.pironi и
се отбелязват като доставени. Оттам нататък живеят там, заедно със сесиите и
паметта — тоест разархивирането на следваща версия ги запазва.


КАК ДА ГО ПУСНЕШ С ПЪЛНИ ПРАВА
------------------------------

По подразбиране Pironi е предпазлив: чете свободно, но за всяка промяна пита,
а шелът е ограничен до работната папка. Ако искаш да работи без да пита:

   .\pironi.bat --provider deepseek --model deepseek-v4-flash `
     --approval auto `
     --shell-scope unrestricted `
     --read-scope unrestricted `
     --personal-context allow `
     --workspace "$env:USERPROFILE"

Обратният апостроф на края на реда е продължение в PowerShell. Ако копирането
през няколко реда се разваля — а през чат прозорец обикновено се разваля —
това е същата команда на един ред:

   .\pironi.bat --provider deepseek --model deepseek-v4-flash --approval auto --shell-scope unrestricted --read-scope unrestricted --personal-context allow --workspace "$env:USERPROFILE"

В Command Prompt, не в PowerShell, същото е:

   pironi.bat --provider deepseek --model deepseek-v4-flash --approval auto --shell-scope unrestricted --read-scope unrestricted --personal-context allow --workspace "%USERPROFILE%"

Какво прави всеки от тях:

   --approval auto              променя и трие файлове, и изпълнява шел
                                команди, без да пита. По подразбиране е
                                read-only, тоест отказва всяка промяна.

   --approval ask               пита преди всяка промяна. На подканата
                                отговаряш y, N или a — "a" значи "не питай
                                повече за този инструмент до края на сесията".
                                Работят и "да" и "винаги". Всичко останало се
                                чете като отказ.

   --shell-scope unrestricted   шелът достига цялата машина, включително UNC
                                пътища към други машини. По подразбиране е
                                workspace.

   --read-scope unrestricted    четенето достига всички дискове. Това вече е
                                подразбирането — тук е само за пълнота.

   --personal-context allow     зарежда SOUL.md и USER.md. Също подразбиране.

   --workspace "..."            къде действат промените. Без него работната
                                папка е тази, в която стоиш, а при двоен клик
                                върху pironi.bat — %USERPROFILE%.

ДВЕ ИЗКЛЮЧЕНИЯ ОСТАВАТ ВИНАГИ, и само те.

Хранилищата за пароли и ключове. Дори при unrestricted, достъп до .ssh, до
DPAPI ключовете и до Windows Credentials изисква изричното ти "да" при всяко
обръщение. Там "a" важи само за това обръщение и ти го казва.

Местенето на работната папка. --approval auto значи "действай без да питаш", а
не "и си мести границата на това какво можеш да пипаш". Същото важи за писане в
SOUL.md, USER.md и CLAUDE.md — това са собствените указания на агента.

Всичко останало при --approval auto минава без питане, включително шел
командите.

Преди да ползваш --approval auto: имай backup или OneDrive history и задавай
тесни задачи. Агентът ще пише и трие без да пита.


ПРЕНОСИМ РЕЖИМ
--------------

   .\pironi.bat --portable --provider deepseek --model deepseek-v4-flash

Тогава сесиите, скиловете и паметта живеят в .pironi вътре в тази папка, а не
в %USERPROFILE%. За копие на флашка, което не оставя нищо по машината.

Без този флаг всичко е в %USERPROFILE%\.pironi и се дели с всяка друга версия
на Pironi — което е смисълът: обновяването е разархивиране на нова папка.


САМОЛИЧНОСТ И ЛИЧНИ ДАННИ
-------------------------

Преименувай образците и ги сложи в %USERPROFILE%\.pironi:

   SOUL.example.md  ->  %USERPROFILE%\.pironi\SOUL.md
   USER.example.md  ->  %USERPROFILE%\.pironi\USER.md

Точното изписване има значение: зарежда се SOUL.md, а не soul.md.

Двата файла се наслагват по път — %USERPROFILE%\.pironi е основата, следват
.pironi папките надолу към работната. По-близкият до работната папка надделява
при противоречие. CLAUDE.md работи по същия начин, но стои направо в папките,
не в .pironi.

СЪДЪРЖАНИЕТО ИМ СЕ ИЗПРАЩА НА ОБЛАЧНИЯ МОДЕЛ при всеки ход. Ако не искаш това:

   --personal-context deny     никога не ги зарежда
   --personal-context auto     само при локален модел (Ollama)


ДРУГА РАБОТНА ПАПКА
-------------------

   .\pironi.bat --workspace "C:\Users\ТИ\Documents\проект" --provider deepseek --model deepseek-v4-flash

Кавичките са задължителни при интервали в пътя. Pironi създава папката, ако я
няма.


ЗАДАЧА С КИРИЛИЦА ИЛИ КАВИЧКИ
-----------------------------

Не я подавай през командния ред — cmd.exe я разваля, преди Java да я види.
Запиши я като UTF-8 файл:

   Set-Content -Path .\task.txt -Value 'Прегледай проекта и обобщи риска.' -Encoding utf8
   .\pironi.bat --provider deepseek --model deepseek-v4-flash --no-interactive --task-file .\task.txt


ПОЛЕЗНИ КОМАНДИ В СЕСИЯТА
-------------------------

   /doctor      какво вижда Pironi: терминал, пътища, инструменти
   /skills      кои скилове има и кои са дошли с изданието
   /findings    какво помни от предишни пускания и колко струва това
   /workspace   къде действат промените, и как да се премести
   /access      кои инструменти са разрешени
   /help        всички команди
