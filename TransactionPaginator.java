	public <A, B> List<B> execute(Collection<A> inputs, Function<Collection<A>, Collection<B>> function, int pageSize) {

		List<A> list = Lists.newArrayList(inputs);
		int maxPage = list.size() % pageSize == 0 ?
				list.size() / pageSize :
				list.size() / pageSize + 1;
		List<B> results = Lists.newArrayList();
		for(int page = 0; page< maxPage; page++) {
			int startPoint = page * pageSize;
			int endPoint = Math.min(startPoint + pageSize, list.size());

			List<A> listInPage = list.subList(startPoint, endPoint);
			Collection<B> resultsInPage = txManager.inItsOwnTxWithResult(
					() -> function.apply(listInPage));
			results.addAll(resultsInPage);
		}
		return results;
	}
