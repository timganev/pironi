PIRONI PORTABLE FOR WINDOWS 11
==============================

Не са нужни admin права, Maven или инсталирана Java.

1. Разархивирай ZIP файла в папка, в която имаш право да пишеш,
   например Documents\Pironi.

2. Отвори PowerShell в разархивираната папка.

3. Задай API ключа само за текущия PowerShell прозорец:

   $env:DEEPSEEK_API_KEY = "твоят-ключ"

4. Стартирай Pironi:

   .\pironi.bat --provider deepseek --model deepseek-v4-flash --context 131072 --max-output-tokens 16384 --max-turns 30 --activity auto

PowerShell изисква .\ пред локален executable. Горната env променлива живее
само до затварянето на прозореца. За перманентна user променлива без admin
права изпълни веднъж и после отвори нов PowerShell:

   [Environment]::SetEnvironmentVariable("DEEPSEEK_API_KEY", "твоят-ключ", "User")

Без допълнителни аргументи launcher-ът използва:

   workspace:    %USERPROFILE%
   search roots: %USERPROFILE%
   shell scope:  user
   memory:       папката .pironi до pironi.bat
   context:      .pironi\SOUL.md и .pironi\USER.md

Така агентът може да намира, чете и променя файлове от Downloads, Desktop и
Documents. При --activity auto тези операции и shell командите не искат
потвърждение — използвай backup/OneDrive history и задавай тесни задачи.

Sessions и skills също се пазят в тази portable .pironi папка. Така остават с
Pironi, когато преместиш цялата разархивирана директория.

SOUL.md и USER.md се наслагват йерархично: глобалните файлове от
%USERPROFILE%\.pironi са основа, следват portable .pironi и файловете в
.pironi папките по пътя към workspace-а. Най-близкият до workspace-а слой има
предимство при конфликт. CLAUDE.md използва същия модел по директориите.

SOUL.md и USER.md се изпращат към избрания cloud модел. Ако не желаеш това,
добави --personal-context deny след останалите аргументи.

Можеш да подадеш друга workspace папка с:

   .\pironi.bat --workspace "C:\Users\ТВОЕТО-ИМЕ\Documents\project" --provider deepseek --model deepseek-v4-flash --activity auto

Pironi създава последната workspace папка, ако още не съществува. Кавичките са
задължителни, когато пътят съдържа интервали.

За one-shot задача с кирилица, кавички или други Unicode символи запиши prompt-а
като UTF-8 файл и използвай --task-file, вместо да го подаваш през cmd.exe:

   Set-Content -Path .\task.txt -Value 'Прегледай проекта и обобщи риска. ✓' -Encoding utf8
   .\pironi.bat --provider deepseek --model deepseek-v4-flash --no-interactive --task-file .\task.txt --activity auto

Не мести pironi.bat, pironi.jar или runtime поотделно. Те трябва да останат
заедно в разархивираната папка.
