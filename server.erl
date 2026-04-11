-module(server).
-export([start/1, handle_client/1]).

start(Gate) ->
    try ets:new(utilizadores, [set, public, named_table])
    catch _:_ -> ok 
    end,

    try ets:new(rankings, [ordered_set, public, named_table, {keypos, 2}]) 
    catch _:_ -> ok end,

    {ok, ListenSocket} = gen_tcp:listen(Gate, [binary, {packet, 0}, {active, false}, {reuseaddr, true}]),
    io:format("Servidor ligado na porta ~p. À espera de jogadores...~n", [Gate]),
    register(match_maker, spawn(fun() -> match_maker_loop([], 0) end)),
    accept_loop(ListenSocket).

accept_loop(ListenSocket) ->
    {ok, Socket} = gen_tcp:accept(ListenSocket),
    
    % Processo para o cliente
    Pid = spawn(server, handle_client, [Socket]),
    
    % Damos o controlo do socket ao novo processo
    gen_tcp:controlling_process(Socket, Pid),
    accept_loop(ListenSocket).

handle_client(Socket) ->
    % Mensagem Inicial ao dar Connect
    gen_tcp:send(Socket, <<"\n*** Bem-vindo ao Mini-Jogo Concorrente! ***\nComandos disponiveis:\n  - REGISTER,username,password\n  - LOGIN,username,password\n  - CANCEL,username,password\n">>),
    client_loop(Socket).

% Criamos um loop separado para as mensagens seguintes
client_loop(Socket) ->
    case gen_tcp:recv(Socket, 0) of
        {ok, Data} ->
            CleanData = string:trim(binary_to_list(Data)),
            Tokens = string:tokens(CleanData, ","),

            case Tokens of
                [Cmd | Rest] ->
                    LowerCmd = string:lowercase(Cmd),
                    process_command(Socket, LowerCmd, Rest);
                [] -> 
                    gen_tcp:send(Socket, <<"Erro: Nao enviaste nada.\n">>)
            end,
            % Volta a pedir input
            gen_tcp:send(Socket, <<"\nEscreve o teu comando: ">>),
            client_loop(Socket);

        {error, closed} -> 
            io:format("Um cliente saiu.\n"),
            ok
    end.

%% --- Lógica de Comandos ---

%% --- Lógica de Comandos Amigável ---

% REGISTER: Criação de conta 
process_command(Socket, "register", [User, Pass]) ->
    case ets:insert_new(utilizadores, {User, Pass}) of
        true -> 
            gen_tcp:send(Socket, [<<"Sucesso: O utilizador '">>, list_to_binary(User), <<"' foi criado!\n">>]);
        false -> 
            gen_tcp:send(Socket, <<"Erro: Esse nome ja existe. Escolhe outro.\n">>)
    end;
process_command(Socket, "register", _) ->
    gen_tcp:send(Socket, <<"Instrucao: Usa o formato: REGISTER,teu_nome,tua_pass\n">>);

% LOGIN: Autenticação de jogador [cite: 15]
process_command(Socket, "login", [User, Pass]) ->
    case ets:lookup(utilizadores, User) of
        [{User, Pass}] -> 
            gen_tcp:send(Socket, [<<"Sucesso: Ola ">>, list_to_binary(User), <<"!\n">>]),
            % Entra no loop do menu e para o loop de autenticação
            menu_loop(Socket, User); 
        _ -> 
            gen_tcp:send(Socket, <<"Erro: Credenciais invalidas.\n">>)
    end;
process_command(Socket, "login", _) ->
    gen_tcp:send(Socket, <<"Instrucao: Usa o formato: LOGIN,teu_nome,tua_pass\n">>);

% CANCEL: Cancelamento de registo 
process_command(Socket, "cancel", [User, Pass]) ->
    case ets:lookup(utilizadores, User) of
        [{User, Pass}] -> 
            % Remove o utilizador da tabela ETS
            ets:delete(utilizadores, User),
            gen_tcp:send(Socket, [<<"Sucesso: A conta '">>, list_to_binary(User), <<"' foi removida com exito.\n">>]);
        _ -> 
            gen_tcp:send(Socket, <<"Erro: Nao foi possivel cancelar. Verifica o username e password.\n">>)
    end;
process_command(Socket, "cancel", _) ->
    gen_tcp:send(Socket, <<"Instrucao: Para apagar a tua conta usa: CANCEL,teu_nome,tua_pass\n">>);

% Comando desconhecido
process_command(Socket, _, _) ->
    gen_tcp:send(Socket, <<"Erro: Comando desconhecido.\nComandos disponiveis: REGISTER, LOGIN, CANCEL\n">>).

menu_loop(Socket, User) ->
    gen_tcp:send(Socket, <<"\n--- MENU PRINCIPAL ---\n1. Procurar Partida (Entrar na Fila)\n2. Ver Top Pontuacoes (Rankings)\n3. Logout\nEscolha: ">>),
    
    case gen_tcp:recv(Socket, 0) of
        {ok, Data} ->
            Opcao = string:trim(binary_to_list(Data)),
            case Opcao of
                "1" -> 
                    gen_tcp:send(Socket, <<"A entrar na fila de espera...\n">>),
                    % Aqui chamaremos o Match Maker mais à frente
                    wait_loop(Socket, User); 
                "2" -> 
                    % Mostrar rankings 
                    show_rankings(Socket),
                    menu_loop(Socket, User);
                "3" -> 
                    gen_tcp:send(Socket, <<"Logout efetuado. Ate a proxima!\n">>),
                    handle_client(Socket); % Volta para o ecrã inicial
                _ -> 
                    gen_tcp:send(Socket, <<"Opcao invalida.\n">>),
                    menu_loop(Socket, User)
            end;
        {error, closed} -> ok
    end.

show_rankings(Socket) ->
    Lista = ets:tab2list(rankings),
    gen_tcp:send(Socket, <<"RANK_START\n">>), % Avisa o Java para começar a guardar
    case Lista of
        [] -> gen_tcp:send(Socket, <<"Ainda nao ha recordes registados.\n">>);
        _  -> [gen_tcp:send(Socket, [User, <<": ">>, integer_to_binary(Vits), <<" vitorias\n">>]) 
               || {User, Vits} <- Lista]
    end,
    gen_tcp:send(Socket, <<"RANK_END\n">>).

% Iniciamos o loop com a Fila vazia e 0 partidas a decorrer
match_maker_loop(Fila, NumPartidasAtivas) ->
    receive
        {entrar_na_fila, Username, Pid} ->
            Ref = monitor(process, Pid),
            NovaFila = Fila ++ [{Username, Pid, Ref}],
            
            % LOG DE DEPURAÇÃO
            io:format("DEBUG: ~p entrou na fila. Total na fila: ~p~n", [Username, length(NovaFila)]),
            
            if 
                length(NovaFila) >= 3, NumPartidasAtivas < 4 ->
                    % Escolhemos os primeiros 3 ou 4 jogadores (vamos levar 3 para testar mais fácil)
                    {JogadoresParaJogo, RestoDaFila} = lists:split(3, NovaFila),
                    
                    % Criamos o processo da Partida
                    % Passamos self() para a partida nos avisar quando terminar
                    spawn(fun() -> inicializar_jogo(JogadoresParaJogo, self()) end),
                    
                    io:format("Partida lancada com 3 jogadores! Partidas ativas: ~p~n", [NumPartidasAtivas + 1]),
                    match_maker_loop(RestoDaFila, NumPartidasAtivas + 1);
                
                true -> 
                    match_maker_loop(NovaFila, NumPartidasAtivas)
            end;

        {'DOWN', Ref, process, _Pid, _Reason} ->
            NovaFila = lists:keydelete(Ref, 3, Fila),
            io:format("Alguem saiu da fila. Jogadores restantes: ~p~n", [length(NovaFila)]),
            match_maker_loop(NovaFila, NumPartidasAtivas);

        {partida_terminou} ->
            % Quando uma partida acaba, libertamos uma vaga para uma nova
            io:format("Uma partida terminou. Vaga libertada.~n"),
            match_maker_loop(Fila, NumPartidasAtivas - 1)
    end.

wait_loop(Socket, User) ->
    % 1. Entra na fila apenas UMA vez
    match_maker ! {entrar_na_fila, User, self()},
    
    % 2. Ativa o modo de receção de eventos do Socket
    inet:setopts(Socket, [{active, true}]),
    
    % 3. Salta para a função que apenas espera pelas mensagens
    wait_loop_atento(Socket, User).

wait_loop_atento(Socket, User) ->
    receive
        {tcp_closed, _Socket} ->
            io:format("Jogador ~p desconectou-se.~n", [User]),
            exit(done);

        {começar_partida, _Dados} ->
            % Colocamos o socket em modo ativo para o game_loop receber as teclas
            inet:setopts(Socket, [{active, true}]),
            gen_tcp:send(Socket, <<"A partida vai comecar!\n">>),
            % Iniciamos o loop de jogo com posição inicial {100, 100}
            game_loop(Socket, {100, 100})

        after 30000 -> 
            show_rankings(Socket),
            gen_tcp:send(Socket, <<"\nContinuas em fila de espera. Aguarda...\n">>),
            wait_loop_atento(Socket, User)
    end.

% Função auxiliar para continuar a espera sem reenviar mensagem ao match_maker
wait_loop_cont(Socket, User) ->
    receive
        {começar_partida, _} -> ok % Lógica de jogo
    after 30000 -> 
        show_rankings(Socket),
        wait_loop_cont(Socket, User)
    end.

inicializar_jogo(ListaJogadores, MatchMakerPid) ->
    % 1. Avisar cada processo de jogador que o jogo começou
    [Pid ! {começar_partida, []} || {_User, Pid, _Ref} <- ListaJogadores],
    
    io:format("Sessao de jogo iniciada para 120 segundos.~n"),
    
    % 2. Simular a duração da partida
    timer:sleep(120000), 
    
    % 3. Avisar os jogadores que o jogo acabou
    [Pid ! {fim_de_jogo} || {_User, Pid, _Ref} <- ListaJogadores],
    
    % 4. Avisar o Match Maker para libertar a vaga
    MatchMakerPid ! {partida_terminou},
    io:format("Sessao de jogo finalizada.~n").

% Exemplo de como o estado do jogador pode ser controlado
% Estado = {X, Y}
game_loop(Socket, {X, Y}) ->
    % Enviamos a posição atual para o Java desenhar
    % Usamos \n no final para o readLine() do Java funcionar
    Msg = list_to_binary(io_lib:format("POS,~p,~p\n", [X, Y])),
    gen_tcp:send(Socket, Msg),
    
    receive
        {tcp, Socket, Data} ->
            Comando = string:trim(binary_to_list(Data)),
            % Lógica de movimento simples
            NovaPos = case Comando of
                "UP"    -> {X, Y - 10};
                "DOWN"  -> {X, Y + 10};
                "RIGHT" -> {X + 10, Y};
                "LEFT"  -> {X - 10, Y};
                _       -> {X, Y}
            end,
            game_loop(Socket, NovaPos);

        {fim_de_jogo} ->
            gen_tcp:send(Socket, <<"O tempo acabou! A voltar ao menu...\n">>),
            % Opcional: Voltar ao menu_loop se quiseres continuar a jogar
            ok; 
            
        {tcp_closed, _Socket} -> 
            exit(done)

    after 40 -> % Aproximadamente 25 FPS para um movimento fluido
        game_loop(Socket, {X, Y})
    end.