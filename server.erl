-module(server).
-export([start/1, handle_client/1]).

start(Gate) ->
    try ets:new(utilizadores, [set, public, named_table])
    catch _:_ -> ok end,

    try ets:new(rankings, [ordered_set, public, named_table, {keypos, 2}])
    catch _:_ -> ok end,

    % NOVO — tabela de sessões ativas
    try ets:new(sessoes_ativas, [set, public, named_table])
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
            gen_tcp:send(Socket, <<"\nEscreve o teu comando: ">>),
            client_loop(Socket);

        % ANTES: {error, closed} -> ...
        % AGORA: apanha qualquer erro de socket
        {error, _Reason} ->
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
            % NOVO — verifica se já há sessão ativa
            case ets:lookup(sessoes_ativas, User) of
                [] ->
                    ets:insert(sessoes_ativas, {User, self()}),
                    gen_tcp:send(Socket, [<<"Sucesso: Ola ">>, list_to_binary(User), <<"!\n">>]),
                    menu_loop(Socket, User);
                _ ->
                    gen_tcp:send(Socket, <<"Erro: Esta conta ja esta em uso.\n">>)
            end;
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
                    wait_loop(Socket, User); 
                "2" -> 
                    show_rankings(Socket),
                    menu_loop(Socket, User);
                "3" -> 
                    ets:delete(sessoes_ativas, User),
                    gen_tcp:send(Socket, <<"Logout efetuado. Ate a proxima!\n">>),
                    handle_client(Socket);
                _ -> 
                    gen_tcp:send(Socket, <<"Opcao invalida.\n">>),
                    menu_loop(Socket, User)
            end;
        % ANTES: {error, closed} -> ok
        % AGORA: apanha qualquer erro de socket
        {error, _Reason} ->
            ets:delete(sessoes_ativas, User),
            io:format("~s desconectou do menu.~n", [User]),
            ok
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

match_maker_loop(Fila, NumPartidasAtivas) ->
    receive
        {entrar_na_fila, Username, Pid} ->
            Ref = monitor(process, Pid),
            % Adicionamos o estado 'false' (não está pronto)
            NovaFila = Fila ++ [{Username, Pid, Ref, false}],
            io:format("DEBUG: ~p entrou na fila. Esperando 'Ready'...~n", [Username]),
            match_maker_loop(NovaFila, NumPartidasAtivas);

        {ready, Pid} ->
            % Quando o Java envia "READY", atualizamos o estado na lista
            NovaFila = lists:map(fun({U, P, R, S}) -> 
                if P == Pid -> {U, P, R, true}; true -> {U, P, R, S} end 
            end, Fila),
            
            % Verificar se as condições de início foram atingidas
            verificar_inicio_partida(NovaFila, NumPartidasAtivas);

        {'DOWN', Ref, process, _Pid, _Reason} ->
            NovaFila = lists:keydelete(Ref, 3, Fila),
            match_maker_loop(NovaFila, NumPartidasAtivas);

        {partida_terminou} ->
            match_maker_loop(Fila, NumPartidasAtivas - 1)
    end.

verificar_inicio_partida(Fila, NumPartidasAtivas) ->
    Prontos = [P || P <- Fila, element(4, P) == true],
    TotalNaFila = length(Fila),
    NumProntos = length(Prontos),

    % Lógica: 
    % 1. Se chegarmos a 4 e todos derem Ready -> Começa com 4.
    % 2. Se tivermos 3, todos derem Ready e não houver um 4º a entrar -> Começa com 3.
    if 
        NumProntos == 4, NumPartidasAtivas < 4 ->
            iniciar_jogo_com_n(Fila, 4, NumPartidasAtivas);
        NumProntos == 3, TotalNaFila == 3, NumPartidasAtivas < 4 ->
            iniciar_jogo_com_n(Fila, 3, NumPartidasAtivas);
        true -> 
            match_maker_loop(Fila, NumPartidasAtivas)
    end.

iniciar_jogo_com_n(Fila, N, NumPartidasAtivas) ->
    {Jogadores, Resto} = lists:split(N, Fila),
    % Limpamos o campo 'ReadyStatus' para a função inicializar_jogo não se baralhar
    JogadoresLimpos = [{U, P, R} || {U, P, R, _S} <- Jogadores],
    spawn(fun() -> inicializar_jogo(JogadoresLimpos, self()) end),
    io:format("Partida iniciada com ~p jogadores prontos!~n", [N]),
    match_maker_loop(Resto, NumPartidasAtivas + 1).

wait_loop(Socket, User) ->
    % 1. Entra na fila apenas UMA vez
    match_maker ! {entrar_na_fila, User, self()},
    
    % 2. Ativa o modo de receção de eventos do Socket
    inet:setopts(Socket, [{active, true}]),
    
    % 3. Salta para a função que apenas espera pelas mensagens
    wait_loop_atento(Socket, User).

wait_loop_atento(Socket, User) ->
    receive
        % wait_loop_atento
        {tcp_closed, _Socket} ->
            ets:delete(sessoes_ativas, User),
            io:format("~s fechou a aplicação enquanto estava na fila.~n", [User]),
            exit(done);
        {tcp, Socket, Data} ->
            case string:trim(binary_to_list(Data)) of
                "READY" -> 
                    match_maker ! {ready, self()},
                    gen_tcp:send(Socket, <<"Estado: Estás pronto! A aguardar outros...\n">>),
                    wait_loop_atento(Socket, User);
                _ -> 
                    wait_loop_atento(Socket, User)
            end;
        % Capturamos o MestrePid aqui (antigo _Dados)
        {começar_partida, MestrePid} ->
            inet:setopts(Socket, [{active, true}]),
            gen_tcp:send(Socket, <<"A partida vai comecar!\n">>),
            % Chamamos a função com 3 argumentos para bater certo com a definição
            game_loop(Socket, User, MestrePid)

        after 30000 -> 
            gen_tcp:send(Socket, <<"Ainda estas na fila. A aguardar jogadores...\n">>),
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
    % {User, {X, Y}, {VelX, VelY}, Angulo, Massa, Pid}
    EstadoInicial = [
        begin
            Ref = monitor (process, Pid),
            {User, {100.0, 100.0 + (I*50.0)}, {0.0, 0.0}, 0.0, 50.0, Pid, Ref}
        end
        || {I, {User, Pid, _Ref}} <- lists:zip(lists:seq(1, length(ListaJogadores)), ListaJogadores) ],

    [Pid ! {começar_partida, self()} || {_User, Pid, _Ref} <- ListaJogadores],
    self() ! tick, 
    partida_loop(EstadoInicial, MatchMakerPid).

partida_loop(Estado, MatchMakerPid) ->
    receive
        {mover, User, Tecla} ->
            case Tecla of 
                "ESC" ->
                    EstadoSaiu = lists:filter(fun({U, _Pos, _Vel, _Ang, _M, _Pid, _R}) -> User == U end, Estado),
                    NovoEstado = lists:filter(fun({U, _Pos, _Vel, _Ang, _M, _Pid, _R}) -> User =/= U end, Estado),

                    broadcast_saiu(EstadoSaiu),

                    check_winner(NovoEstado, MatchMakerPid);
                    
                _ ->
                    % 1. Encontra o jogador na lista e aplica a aceleração/torque
                    NovoJogador = calcular_fisica(User, Tecla, Estado),
                    % 2. Substitui o jogador antigo pelo novo na lista global
                    NovoEstado = lists:keyreplace(User, 1, Estado, NovoJogador),
                    partida_loop(NovoEstado, MatchMakerPid)
                end;

        tick ->
            % 3. ATUALIZAÇÃO DA INÉRCIA: Move todos os jogadores baseando-se na velocidade atual
            EstadoComInercia = aplicar_movimento_global(Estado),
            
            % 4. Broadcast do estado atualizado
            broadcast_estado(EstadoComInercia),
            
            erlang:send_after(30, self(), tick),
            partida_loop(EstadoComInercia, MatchMakerPid);

        {'DOWN', Ref, process, _Pid, _Reason} ->
            NovoEstado = lists:filter(fun({_User, _Pos, _Vel, _Ang, _M, _Pid, R}) -> R =/= Ref end, Estado),
            check_winner(NovoEstado, MatchMakerPid);

        {fim_de_jogo} ->
            MatchMakerPid ! {partida_terminou}
        
    end.


% Constantes da física
-define(FORCA, 10.0).   % era 5.0 — muito fraco
-define(TORQUE, 0.15).  % era 0.1
-define(ATRITO, 0.995). % era 0.98 — travava em ~1 segundo

check_winner(Estado, MatchMakerPid) ->
    case(length(Estado)) of
        1 -> 
            [Winner] = Estado, 
            {UserWinner, _, _, _, _, _, _} = Winner,
            io:format("Vencedor: ~p~n", [UserWinner]),

            broadcast_fim(Estado),

            MatchMakerPid ! {partida_terminou};
        _ ->
            partida_loop(Estado, MatchMakerPid)
    end.

calcular_fisica(User, Comando, Estado) ->
    {User, {X, Y}, {VX, VY}, Angulo, Massa, Pid, Ref} = lists:keyfind(User, 1, Estado),
    
    case Comando of
        "UP" -> 
            % Acelera na direção do ângulo atual (F = m * a -> a = F/m)
            AX = math:cos(Angulo) * (?FORCA / Massa),
            AY = math:sin(Angulo) * (?FORCA / Massa),
            {User, {X, Y}, {VX + AX, VY + AY}, Angulo, Massa, Pid, Ref};
        "LEFT" -> 
            {User, {X, Y}, {VX, VY}, Angulo - ?TORQUE, Massa, Pid, Ref};
        "RIGHT" -> 
            {User, {X, Y}, {VX, VY}, Angulo + ?TORQUE, Massa, Pid, Ref};
        _ -> 
            {User, {X, Y}, {VX, VY}, Angulo, Massa, Pid, Ref}
    end.

% Esta função corre para TODOS os jogadores a cada 30ms
aplicar_movimento_global(Estado) ->
    lists:map(fun({U, {X, Y}, {VX, VY}, Ang, M, Pid, Ref}) ->
        NVX = VX * ?ATRITO,
        NVY = VY * ?ATRITO,

        NX = max(0.0, min(800.0, X + NVX)),
        NY = max(0.0, min(600.0, Y + NVY)),

        FVX = if NX =:= 0.0; NX =:= 800.0 -> 0.0; true -> NVX end,
        FVY = if NY =:= 0.0; NY =:= 600.0 -> 0.0; true -> NVY end,

        {U, {NX, NY}, {FVX, FVY}, Ang, M, Pid, Ref}
    end, Estado).

% Funções auxiliares simples para Erlang não se queixar
cos(A) -> math:cos(A).
sin(A) -> math:sin(A).
% Envia o estado para o processo de cada jogador
broadcast_estado(Estado) ->
    StringEstado = "DATA" ++ lists:foldl(fun({U, {X, Y}, _, Ang, M, _Pid, _}, Acc) -> 
        % Usamos float(X) para garantir que o io_lib não crasha se receber um inteiro
        JogadorData = io_lib:format(",~s:~.2f:~.2f:~.2f:~.2f", 
                                    [U, float(X), float(Y), float(Ang), float(M)]),
        Acc ++ lists:flatten(JogadorData)
    end, "", Estado),
    
    [Pid ! {actualizar_mundo, StringEstado} || {_, _, _, _, _, Pid, _} <- Estado].

broadcast_fim(Estado) ->
    [Pid ! {fim_de_jogo} || {_, _, _, _, _, Pid, _} <- Estado].

broadcast_saiu(Estado) ->
    [Pid ! {saiu} || {_, _, _, _, _, Pid, _} <- Estado].
% Exemplo de como o estado do jogador pode ser controlado
% Estado = {X, Y}
game_loop(Socket, User, MestrePid) ->
    receive
        % 1. Recebe tecla do Java e avisa o Mestre
        {tcp, Socket, Data} ->
            Tecla = string:trim(binary_to_list(Data)),
            MestrePid ! {mover, User, Tecla},
            game_loop(Socket, User, MestrePid);

        % 2. Recebe o estado global do Mestre e envia para o Java
        {actualizar_mundo, StringEstado} ->
            gen_tcp:send(Socket, list_to_binary(StringEstado ++ "\n")),
            game_loop(Socket, User, MestrePid);

        {fim_de_jogo} ->
            gen_tcp:send(Socket, <<"FIM\n">>),
            ok;
        {saiu} ->
            ets:delete(sessoes_ativas, User),
            gen_tcp:send(Socket, <<"FIM\n">>),
            io:format("DESISTÊNCIA: ~s abandonou a partida em curso!~n", [User]),
            exit(done);
        % game_loop
        {tcp_closed, _Socket} ->
            ets:delete(sessoes_ativas, User),  % NOVO
            io:format("DESISTÊNCIA: ~s abandonou a partida em curso!~n", [User]),
            exit(done)
    end.