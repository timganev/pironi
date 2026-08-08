PIRONI PORTABLE FOR WINDOWS 11
==============================

Не са нужни admin права, Maven или инсталирана Java.

1. Разархивирай ZIP файла в папка, в която имаш право да пишеш,
   например Documents\Pironi.

2. Отвори Command Prompt в разархивираната папка.

3. Задай API ключа само за текущия прозорец:

   set DEEPSEEK_API_KEY=твоят-ключ

4. Стартирай Pironi:

   pironi.bat --provider deepseek --model deepseek-v4-flash --context 131072 --max-output-tokens 16384 --max-turns 30 --activity auto

Без допълнителни аргументи launcher-ът използва:

   workspace:    %USERPROFILE%\Documents\PironiWorkspace
   search roots: %USERPROFILE% (read-only достъп чрез find_files/read_file)
   memory:       папката .pironi до pironi.bat
   context:      .pironi\SOUL.md и .pironi\USER.md

Така агентът може да намира и чете файлове от Downloads, Desktop и Documents,
но може да променя файлове само в PironiWorkspace. Папката се създава автоматично.

Sessions и skills също се пазят в тази portable .pironi папка. Така остават с
Pironi, когато преместиш цялата разархивирана директория.

SOUL.md и USER.md се наслагват йерархично: глобалните файлове от
%USERPROFILE%\.pironi са основа, следват portable .pironi и файловете в
.pironi папките по пътя към workspace-а. Най-близкият до workspace-а слой има
предимство при конфликт. CLAUDE.md използва същия модел по директориите.

SOUL.md и USER.md се изпращат към избрания cloud модел. Ако не желаеш това,
добави --personal-context deny след останалите аргументи.

Можеш да подадеш друга workspace папка с:

   --workspace "C:\Users\ТВОЕТО-ИМЕ\Documents\project"

Pironi създава последната workspace папка, ако още не съществува. Кавичките са
задължителни, когато пътят съдържа интервали.

Не мести pironi.bat, pironi.jar или runtime поотделно. Те трябва да останат
заедно в разархивираната папка.
